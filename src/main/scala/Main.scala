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
import scalafx.scene.input._
import scalafx.scene.layout._
import scalafx.scene.paint.Color._

object Communication {
    private val initialMessage = SerialPort.Information(null)

    val messageFromSerial: Subject[SerialPort.OutputMessage] = SerializedSubject(BehaviorSubject(initialMessage))
}

object Main extends JFXApp {
    private val system = ActorSystem()
    private val serialPort = system.actorOf(SerialPort.props)
    system.actorOf(SerialWatcher.props)

    private val availablePortsPath = ObservableBuffer.empty[String]

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
            appendTerminalText(commandInput.text.value)

            //TODO: serialPort ! SerialPort.SendCommand(terminalArea.text.value)
        }
    }

    private val connectButton = new Button {
        text = "Connect"
    }
    connectButton.requestFocus()

    private val availablePortsChoiceBox = new ChoiceBox[String] {
        maxWidth = 200
        alignmentInParent = Pos.BaselineRight
        items = availablePortsPath
    }

    val lineChart = createLineChart()

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
                        appendTerminalText(msg.message)
                    }
                }
                case msg: SerialPort.Error => {
                    appendTerminalText(msg.message)
                }
                case msg: SerialPort.ConnectionClosed => {
                    appendTerminalText(msg.message)

                    connectButton.disable = false
                    connectButton.text = "Connect"
                    connectButton.onAction = handle {
                        onConnectHandle()
                    }

                    commandInput.disable = false
                    availablePortsChoiceBox.disable = false

                    commandInput.disable = true
                }
                case msg: SerialPort.ConnectionSuccess => {
                    appendTerminalText("Successfully connected")

                    commandInput.disable = false

                    connectButton.disable = false
                    connectButton.text = "Disconnect"
                    connectButton.onAction = handle {
                        onDisconnectHandle()
                    }
                }
                case msg: SerialPort.DataReceived => {
                    lineChart.getData.get(1).getData.add(XYChart.Data[Number, Number](msg.time, msg.temperature))
                }
                case msg: SerialPort.NewSerialPort => {
                    availablePortsPath.clear()
                    (availablePortsPath ++ Some(msg.path)).distinct.sorted.foreach { path =>
                        availablePortsPath += path
                    }

                    if (availablePortsPath.length == 1) {
                        availablePortsChoiceBox.selectionModel.value.selectLast()
                    }
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

                    top = createMenuBar()
                    center = lineChart
                    bottom = createBottomPane()
                    right = createRightPane()
                }
            }

            onCloseRequest = handle {
                closeApp()
            }
        }
    }

    private def createMenuBar(): MenuBar = {
        val menu = new Menu("File") {
            items = List(
                new MenuItem("Clear terminal") {
                    accelerator = new KeyCodeCombination(KeyCode.L, KeyCombination.MetaDown)
                    onAction = handle {
                        terminalArea.clear()
                    }
                },
                new MenuItem("Close") {
                    onAction = handle {
                        stage.close()
                        closeApp()
                    }
                }
            )
        }

        new MenuBar {
            useSystemMenuBar = true
            menus.add(menu)
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

        val measuredProfileSeries = new XYChart.Series[Number, Number] {
            name = "Measured"
            data = Seq.empty
        }

        val lineChart = new LineChart[Number, Number](timeAxis, temperatureAxis, ObservableBuffer(
            referenceProfileSeries, measuredProfileSeries))
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
        val portLabel = new Label("Port:") {
            alignmentInParent = Pos.BaselineRight
        }
        GridPane.setConstraints(portLabel, 0, 0, 1, 1)

        GridPane.setConstraints(availablePortsChoiceBox, 1, 0, 1, 1)

        connectButton.onAction = handle {
            onConnectHandle()
        }
        GridPane.setConstraints(connectButton, 1, 1, 1, 1)

        new GridPane {
            hgap = 5
            vgap = 5
            children ++= Seq(portLabel, availablePortsChoiceBox, connectButton)
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
        if (terminalArea.text.value.nonEmpty) {
            terminalArea.appendText("\n")
        }

        terminalArea.appendText(message)
    }

    private def closeApp(): Unit = {
        system.terminate()
    }

    private def onConnectHandle(): Unit = {
        availablePortsChoiceBox.disable = true

        connectButton.disable = true
        connectButton.text = "Connecting..."

        val settings = SerialSettings(115200, 8, twoStopBits = false, Parity.None)
        serialPort ! SerialPort.Open(availablePortsChoiceBox.getSelectionModel.getSelectedItem, settings)
    }

    private def onDisconnectHandle(): Unit = {
        serialPort ! SerialPort.Close()
    }
}
