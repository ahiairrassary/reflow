import java.nio._

import akka.actor._
import akka.io.IO
import akka.util.ByteString
import com.github.jodersky.flow._

import scala.concurrent.duration._

class SerialPort extends Actor {
    import SerialPort._

    private implicit val system = context.system
    private implicit val dispatcher = system.dispatcher

    private var timeoutSchedulerOpt = Option.empty[Cancellable]
    private var readBuffer = ByteString.empty

    def receive = waitForOpen

    override def postStop(): Unit = {
        timeoutSchedulerOpt.foreach(_.cancel())
    }

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
            context.become(waitForOpen)
        }
        case msg: Serial.Opened => {
            Communication.messageFromSerial.onNext(ConnectionSuccess())

            val operator = sender
            context.become(waitForCommunication(operator))
            context.watch(operator)
        }
        case msg: SendCommand => {
            // do nothing
        }
    }

    private def waitForCommunication(operator: ActorRef): Receive = {
        case msg: Close => {
            operator ! Serial.Close
        }
        case msg: SendCommand => {
            operator ! Serial.Write(msg.command)
        }
        case msg: Serial.Received => {
            handleData(msg)
        }
        case Serial.Closed => {
            Communication.messageFromSerial.onNext(ConnectionClosed("Serial operator closed normally"))

            context.unwatch(operator)
            context.become(waitForOpen)
        }
        case ReadTimeout => {
            timeoutSchedulerOpt.foreach(_.cancel())
            timeoutSchedulerOpt = None

            readBuffer = ByteString.empty
            println("Read timeout") // TODO
        }
        case Terminated(`operator`) => {
            Communication.messageFromSerial.onNext(ConnectionClosed("Serial operator crashed"))

            context.become(waitForOpen)
        }
    }

    private def handleData(msg: Serial.Received): Unit = {
        readBuffer ++= msg.data

        val bytes = readBuffer.toArray

        if (bytes.length >= 2) {
            timeoutSchedulerOpt.foreach(_.cancel())
            timeoutSchedulerOpt = None

            val expectedLength = 0xFFFF & extractValue(bytes.take(2)).getShort + 2

            if (bytes.length >= expectedLength) {
                val command = bytes.take(expectedLength)
                readBuffer = readBuffer.drop(expectedLength)

                Communication.messageFromSerial.onNext(DataReceived(ByteString(command)))
            }
            else {
                timeoutSchedulerOpt.foreach(_.cancel())
                timeoutSchedulerOpt = Some(system.scheduler.scheduleOnce(500.milliseconds, self, ReadTimeout))
            }
        }
        else {
            // do nothing
        }
    }
}

object SerialPort {
    // input messages
    case class Open(port: String, settings: SerialSettings)
    case class SendCommand(command: ByteString)
    case class Close()

    val startCommand = SendCommand(ByteString(0x02, 0x00, 0x00, 0xB0))
    val stopCommand = SendCommand(ByteString(0x02, 0x00, 0x01, 0xB0))

    // output messages
    sealed trait OutputMessage
    case class NewSerialPort(path: String) extends OutputMessage
    case class Error(message: String) extends OutputMessage
    case class Information(message: String) extends OutputMessage
    case class ConnectionClosed(message: String) extends OutputMessage
    case class ConnectionSuccess() extends OutputMessage
    case class DataReceived(data: ByteString) extends OutputMessage

    // private messages
    case class ReadTimeout()

    // functions
    def props = Props[SerialPort]

    def extractFloat(bytes: Array[Byte]): Float = {
        extractValue(bytes).getFloat
    }
    def extractValue(bytes: Array[Byte]): ByteBuffer = {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }
}