import java.nio._
import java.nio.charset.StandardCharsets

import akka.actor._
import akka.util.ByteString
import jssc.{SerialPortEvent, SerialPortEventListener, SerialPort}
import org.apache.commons.codec.binary.Hex
import org.apache.commons.lang3.CharUtils

import scala.concurrent.duration._
import scala.util._

class SerialPortActor extends Actor with SerialPortEventListener{
    import SerialPortActor._

    private var port: SerialPort = _

    private implicit val system = context.system
    private implicit val dispatcher = system.dispatcher

    private var timeoutSchedulerOpt = Option.empty[Cancellable]
    private var bytes = Array.empty[Byte]

    def receive = waitForOpen

    override def postStop(): Unit = {
        Try(port.closePort())
        timeoutSchedulerOpt.foreach(_.cancel())
    }

    private def waitForOpen: Receive = {
        case msg: Open => {
            context.become(waitForConnection)

            Try {
                port = new SerialPort(msg.portName)
                port.openPort()
                port.setParams(SerialPort.BAUDRATE_115200, SerialPort.DATABITS_8, SerialPort.STOPBITS_1, SerialPort.PARITY_NONE)
                port.setEventsMask(SerialPort.MASK_RXCHAR)
                port.addEventListener(this)
            } match {
                case Success(_) => {
                    self ! Opened()
                }
                case Failure(throwable) => {
                    self ! OpenError(throwable)
                }
            }
        }
        case msg: SendCommand => {
            // do nothing
        }
    }

    private def waitForConnection: Receive = {
        case msg: OpenError => {
            val reason = msg.throwable.getMessage

            Communication.messageFromSerial.onNext(ConnectionClosed(reason))
            context.become(waitForOpen)
        }
        case msg: Opened => {
            Communication.messageFromSerial.onNext(ConnectionSuccess())

            context.become(waitForCommunication)
        }
        case msg: SendCommand => {
            // do nothing
        }
    }

    def serialEvent(event: SerialPortEvent): Unit = {
        if (event.isRXCHAR) {
            Try {
                port.readBytes(event.getEventValue)
            } match {
                case Success(newBytes) => {
                    handleData(newBytes)
                }
                case Failure(throwable) => {
                    Communication.messageFromSerial.onNext(Error(s"""ERROR: unable to read serial port: ${throwable.getMessage}"""))
                }
            }
        }
        else {
            val message = s"""ERROR: unknown event(${event.getEventType}, ${event.getEventValue})"""
            Communication.messageFromSerial.onNext(Error(message))
        }
    }

    private def waitForCommunication: Receive = {
        case msg: Close => {
            Try(port.closePort())

            Communication.messageFromSerial.onNext(ConnectionClosed("Serial closed"))

            context.become(waitForOpen)
        }
        case msg: SendCommand => {
            val lengthBuffer = ByteBuffer.allocate(2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(msg.command.length.toShort)
                .array()

            val withLength = ByteString(lengthBuffer) ++ msg.command.reverse

            Try {
                port.writeBytes(withLength.toArray)
            } match {
                case Failure(throwable) => {
                    val message = s"""ERROR: during writing: ${throwable.getMessage}"""
                    Communication.messageFromSerial.onNext(Error(message))
                }
                case _ => {
                    // do nothing
                }
            }

        }
        case ReadTimeout => {
            timeoutSchedulerOpt.foreach(_.cancel())
            timeoutSchedulerOpt = None

            bytes = Array.empty[Byte]
            println("Read timeout") // TODO
        }
    }

    private def handleData(newBytes: Array[Byte]): Unit = {
        bytes ++= newBytes

        if (bytes.length >= 2) {
            timeoutSchedulerOpt.foreach(_.cancel())
            timeoutSchedulerOpt = None

            extractPossibleCommand()
        }
        else {
            // wait for enough bytes to read length
        }
    }

    private def extractPossibleCommand(): Unit = {
        val expectedLength = (0xFFFF & extractValue(bytes.take(2)).getShort) + 2

        if (bytes.length >= expectedLength) {
            val command = bytes.take(expectedLength)
            Communication.messageFromSerial.onNext(DataReceived(ByteString(command)))

            bytes = bytes.drop(expectedLength)
            if (bytes.length >= 2) {
                extractPossibleCommand()
            }
            else {
                // wait for enough bytes to read length
            }
        }
        else {
            timeoutSchedulerOpt.foreach(_.cancel())
            timeoutSchedulerOpt = Some(system.scheduler.scheduleOnce(200.milliseconds, self, ReadTimeout))
        }
    }
}

object SerialPortActor {
    // input messages
    case class Open(portName: String)
    case class SendCommand(command: ByteString)
    case class Close()

    val startCommand = SendCommand(ByteString(0xB0, 0x00))
    val stopCommand = SendCommand(ByteString(0xB1, 0x00))

    // output messages
    sealed trait OutputMessage
    case class Error(message: String) extends OutputMessage
    case class Information(message: String) extends OutputMessage
    case class ConnectionClosed(message: String) extends OutputMessage
    case class ConnectionSuccess() extends OutputMessage
    case class DataReceived(data: ByteString) extends OutputMessage

    // private messages
    case class OpenError(throwable: Throwable)
    case class Opened()
    case class ReadTimeout()

    // functions
    def props = Props[SerialPortActor]

    def extractFloat(bytes: Array[Byte]): Float = {
        extractValue(bytes).getFloat
    }

    def extractValue(bytes: Array[Byte]): ByteBuffer = {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }

    def binaryDump(bytes: Array[Byte]): String = {
        val dataHex = Hex.encodeHex(bytes.toArray).grouped(2).map(_.mkString.toUpperCase).mkString(" ")

        val dataAscii = new String(bytes.toArray.map { byte =>
            if (CharUtils.isAsciiPrintable(byte.toChar)) {
                byte
            }
            else {
                '.'.toByte
            }
        }, StandardCharsets.UTF_8)

        s"""($dataHex) : ($dataAscii)"""
    }
}
