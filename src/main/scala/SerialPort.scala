import java.nio._

import akka.actor._
import akka.io.IO
import akka.util.ByteString
import com.github.jodersky.flow._

import scala.concurrent.duration._

class SerialPort extends Actor with ActorLogging {
    import SerialPort._

    private implicit val system = context.system
    private implicit val dispatcher = system.dispatcher

    private var askTemperatureSchedulerOpt: Option[Cancellable] = None

    def receive = waitForOpen

    private def waitForOpen: Receive = {
        case msg: Open => {
            context.become(waitForConnection)
            IO(Serial) ! Serial.Open(msg.port, msg.settings)
        }
        case msg: SendCommand => {
            // do nothing
        }
    }

    private def waitForConnection: Receive = {
        case msg: Serial.CommandFailed => {
            val reason = msg.reason match {
                case _: NoSuchPortException => {
                    "Unable to connect: no such port"
                }
                case throwable => {
                    throwable.getMessage
                }
            }

            Communication.messageFromSerial.onNext(ConnectionClosed(reason))
            cancelScheduler()
            context.become(waitForOpen)
        }
        case msg: Serial.Opened => {
            Communication.messageFromSerial.onNext(ConnectionSuccess())

            val operator = sender
            context.become(waitForCommunication(operator))
            context.watch(operator)

            val command = ByteString(0x02, 0x00, 0x00, 0xA0)
            askTemperatureSchedulerOpt = Some(system.scheduler.schedule(0.second, 1.second, self, SendCommand(command)))
        }
        case msg: SendCommand => {
            // do nothing
        }
    }

    private def waitForCommunication(operator: ActorRef): Receive = {
        case msg: SendCommand => {
            operator ! Serial.Write(msg.command)
        }
        case msg: Serial.Received => {
            val array = msg.data.toArray

            if (array.length < 4) {
                Communication.messageFromSerial.onNext(Information(s"""Received bytes array should at least contains 4 bytes."""))
            }
            else {
                val codeLsb = 0xFF & array(3)
                val codeMsb = 0xFF & array(4)
                val code = (codeLsb << 8) | codeMsb

                code match {
                    case 0xA100 => {
                        if (array.length == 12) {
                            val desiredTemperature = extractFloat(array.drop(4).dropRight(4))
                            val measuredTemperature = extractFloat(array.drop(8))

                            log.info(s"""desiredTemperature: $desiredTemperature, measuredTemperature: $measuredTemperature""")
                        }
                        else {
                            Communication.messageFromSerial.onNext(Information(s"""Unexpected length "${array.length}" for code 0xA100."""))
                        }
                    }
                    case _ => {
                        Communication.messageFromSerial.onNext(Information(s"""Unknown code received: 0x${Integer.toHexString(code).capitalize}."""))
                    }
                }
            }
        }
        case Serial.Closed => {
            Communication.messageFromSerial.onNext(ConnectionClosed("Serial operator closed normally"))

            cancelScheduler()
            context.unwatch(operator)
            context.become(waitForOpen)
        }

        case Terminated(`operator`) => {
            Communication.messageFromSerial.onNext(ConnectionClosed("Serial operator crashed"))

            cancelScheduler()
            context.become(waitForOpen)
        }
    }

    private def cancelScheduler(): Unit = {
        askTemperatureSchedulerOpt.foreach(_.cancel())
    }

    private def extractFloat(bytes: Array[Byte]): Float = {
        extractValue(bytes).getFloat
    }
    private def extractValue(bytes: Array[Byte]): ByteBuffer = {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }
}

object SerialPort {
    // input messages
    case class Open(port: String, settings: SerialSettings)

    case class SendCommand(command: ByteString)

    // output messages
    sealed trait OutputMessage
    case class Information(message: String) extends OutputMessage
    case class ConnectionClosed(message: String) extends OutputMessage
    case class ConnectionSuccess() extends OutputMessage
    case class DataReceived(time: Double, temperature: Double) extends OutputMessage

    // functions
    def props = Props[SerialPort]
}