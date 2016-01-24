import akka.actor._
import akka.io.IO
import com.github.jodersky.flow._

class SerialWatcher extends Actor with ActorLogging {
    import SerialPort._
    import SerialWatcher._

    private implicit val system = context.system

    IO(Serial) ! Serial.Watch()

    def receive = {
        case Serial.CommandFailed(watch: Serial.Watch, err) => {
            Communication.messageFromSerial.onNext(Error(s"""Unable to get watch on ${watch.directory}."""))

            context.stop(self)
        }
        case Serial.Connected(path) => {
            ports.find(path.matches) match {
                case Some(port) => {
                    Communication.messageFromSerial.onNext(NewSerialPort(path))
                }
                case None => {
                    // do nothing
                }
            }
        }
    }
}

object SerialWatcher {
    private val ports = List(
        """/dev/ttyUSB\\d+""",
        """/dev/ttyACM\\d+""",
        """/dev/cu\..*"""
        //"""/dev/tty\..*"""
    )

    def props = Props[SerialWatcher]
}