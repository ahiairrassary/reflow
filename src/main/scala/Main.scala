import akka.actor.ActorSystem
import com.github.jodersky.flow._
import rx.lang.scala._
import rx.lang.scala.subjects._
import scalafx.Includes._
import scalafx.application.JFXApp.PrimaryStage
import scalafx.application._
import scalafx.collections.ObservableBuffer
import scalafx.geometry._
import scalafx.scene.Scene
import scalafx.scene.control._
import scalafx.scene.chart._
import scalafx.scene.layout._
import scalafx.scene.paint.Color._

object Communication {
    private val initialMessage = SerialPort.Information(null)

    val messageFromSerial: Subject[SerialPort.OutputMessage] = SerializedSubject(BehaviorSubject(initialMessage))
}

object Main extends JFXApp {
    private val system = ActorSystem()
    private val serialPort = system.actorOf(SerialPort.props)

    private val measuredProfileSeries = new XYChart.Series[Number, Number] {
        name = "Measured"
        data = Seq.empty
    }

    // global UI widgets
    private val terminalArea = new TextArea {
        editable = false
    }
    terminalArea.text = "Welcome in Reflow (C) Hiairrassary Corp."

    private val commandInput = new TextField {
        prefWidth = Integer.MAX_VALUE
        disable = true
    }

    private val sendCommand = new Button {
        text = "Send"
        disable = true

        onAction = handle {
            appendTerminalText(terminalArea.text.value)

            serialPort ! SerialPort.SendCommand(terminalArea.text.value)
        }
    }

    private val portTextField = new TextField {
        text = "/dev/ttyUSB0"
        alignmentInParent = Pos.BaselineRight
    }

    private val connectButton = new Button {
        text = "Connect"
    }
    connectButton.requestFocus()

    // main UI
    stage = createStage()

    Communication.messageFromSerial.subscribe { message =>
        Platform.runLater {
            message match {
                case msg: SerialPort.Information => {
                    if (msg.message == null) {
                        // do nothing
                    }
                    else {
                        appendTerminalText("Successfully connected")
                    }
                }
                case msg: SerialPort.ConnectionClosed => {
                    appendTerminalText(msg.message)

                    connectButton.disable = false
                    commandInput.disable = false
                    portTextField.disable = false

                    commandInput.disable = true
                }
                case msg: SerialPort.ConnectionSuccess => {
                    appendTerminalText("Successfully connected")

                    commandInput.disable = false
                }
                case msg: SerialPort.DataReceived => {
                    measuredProfileSeries.getData.add(XYChart.Data[Number, Number](msg.time, msg.temperature))
                    //val xSeries = lineChart.data.get().get(0)
                    //xSeries.getData.add(XYChart.Data[Number, Number](newValue, newValue))
                }
            }
        }
    }

    private def createStage(): PrimaryStage = {
        new PrimaryStage {
            title = "Reflow"

            scene = new Scene(1200, 800) {
                root = new BorderPane {
                    fill = LightGray
                    padding = Insets(5)

                    center = createLineChart()
                    bottom = createBottomPane()
                    right = createRightPane()
                }
            }

            onCloseRequest = handle {
                system.terminate()
            }
        }
    }

    private def createLineChart(): LineChart[Number, Number] = {
        val timeAxis = NumberAxis("Time", 0, 300, 20)
        val temperatureAxis = NumberAxis("Temperature", 0, 240, 20)

        val toChartData = (xy: (Double, Double)) => XYChart.Data[Number, Number](xy._1, xy._2)

        val referenceProfileSeries = new XYChart.Series[Number, Number] {
            name = "Reference"
            data = Seq(
                (0.0, 25.0),
                (90.0, 150.0),
                (180.0, 180.0),
                (200.0, 210.0),
                (220.0, 210.0),
                (240.0, 180.0),
                (270.0, 25.0)
            ).map(toChartData)
        }

        val lineChart = new LineChart[Number, Number](timeAxis, temperatureAxis, ObservableBuffer(referenceProfileSeries))
        lineChart.setAnimated(false)
        lineChart.setCreateSymbols(false)

        lineChart
    }

    private def createRightPane(): Pane = {
        new VBox {
            children = Seq(
                createConnectionPane(),
                new Separator()
            )
        }
    }

    private def createConnectionPane(): Pane = {
        val portLabel = new Label("Name:") {
            alignmentInParent = Pos.BaselineRight
        }
        GridPane.setConstraints(portLabel, 0, 0, 1, 1)


        GridPane.setConstraints(portTextField, 1, 0, 1, 1)

        connectButton.onAction = handle {
            portTextField.disable = true
            connectButton.disable = true

            val settings = SerialSettings(115200, 8, twoStopBits = false, Parity.None)
            serialPort ! SerialPort.Open(portTextField.text.value, settings)
        }
        GridPane.setConstraints(connectButton, 1, 1, 1, 1)

        new GridPane {
            hgap = 4
            children ++= Seq(portLabel, portTextField, connectButton)
        }
    }

    private def createBottomPane(): Pane = {
        GridPane.setConstraints(commandInput, 0, 0, 9, 1)
        GridPane.setConstraints(sendCommand, 9, 0, 1, 1)

        val grid = new GridPane {
            hgap = 5
            alignmentInParent = Pos.BaselineRight
            children = Seq(commandInput, sendCommand)
        }

        new VBox {
            spacing = 5
            children = Seq(
                terminalArea,
                grid
            )
        }
    }

    private def appendTerminalText(message: String): Unit = {
        terminalArea.text.value = terminalArea.text.value + "\n" + message
    }
}
