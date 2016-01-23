import java.nio.charset.StandardCharsets

import akka.actor._
import akka.io.IO
import akka.util.ByteString
import com.github.jodersky.flow._

class SerialPort extends Actor with ActorLogging {
    import SerialPort._

    private implicit val system = context.system

    def receive = waitForOpen

    private def waitForOpen: Receive = {
        case msg: Open => {
            context.become(waitForConnection)
            IO(Serial) ! Serial.Open(msg.port, msg.settings)
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
    }

    private def waitForCommunication(operator: ActorRef): Receive = {
        case msg: SendCommand => {
            // TODO: send command
        }
        case msg: Serial.Received => {
            val formatData = (data: ByteString) => {
                data.mkString("[", ",", "]") + " " + new String(data.toArray, StandardCharsets.UTF_8)
            }

            // TODO: notify UI
            log.info(s"Received data: ${formatData(msg.data)}")
        }
        case Serial.Closed => {
            Communication.messageFromSerial.onNext(ConnectionClosed("Serial operator closed normally"))

            context.unwatch(operator)
            context.become(waitForOpen)
        }

        case Terminated(`operator`) => {
            Communication.messageFromSerial.onNext(ConnectionClosed("Serial operator crashed"))

            context.become(waitForOpen)
        }
    }
}

object SerialPort {
    // input messages
    case class Open(port: String, settings: SerialSettings)

    case class SendCommand(command: String)

    // output messages
    sealed trait OutputMessage
    case class Information(message: String) extends OutputMessage
    case class ConnectionClosed(message: String) extends OutputMessage
    case class ConnectionSuccess() extends OutputMessage
    case class DataReceived(time: Double, temperature: Double) extends OutputMessage

    // functions
    def props = Props[SerialPort]
}