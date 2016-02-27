import javafx.beans.value.{ObservableValue, ChangeListener}

import akka.actor.ActorSystem
import akka.util.ByteString
import jssc._
import rx.lang.scala._
import rx.lang.scala.subjects._
import scalafx.Includes._
import scalafx.application.JFXApp.PrimaryStage
import scalafx.application._
import scalafx.collections.ObservableBuffer
import scalafx.geometry._
import scalafx.scene.{Node, Scene}
import scalafx.scene.control._
import scalafx.scene.chart._
import scalafx.scene.input._
import scalafx.scene.layout._
import scalafx.scene.paint.Color._

object Communication {
    private val initialMessage = SerialPortActor.Information(null)

    val messageFromSerial: Subject[SerialPortActor.OutputMessage] = SerializedSubject(BehaviorSubject(initialMessage))
}

object Main extends JFXApp {
    private val system = ActorSystem()
    private val serialPort = system.actorOf(SerialPortActor.props)

    private val availablePortsPath = ObservableBuffer(SerialPortList.getPortNames: _*)

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
        alignmentInParent = Pos.BaselineRight
        text = "Connect"
    }
    connectButton.requestFocus()

    private val startStopButton = new Button {
        text = "Start"
        onAction = handle {
            if (text.value == "Start") {
                cleanReceivedSeries()
                serialPort ! SerialPortActor.startCommand
                text = "Stop"
            }
            else {
                serialPort ! SerialPortActor.stopCommand
                text = "Start"
            }
        }
    }

    private val availablePortsChoiceBox = new ChoiceBox[String] {
        prefWidth = Integer.MAX_VALUE
        maxWidth = 200
        alignmentInParent = Pos.BaselineRight
        items = availablePortsPath
    }

    private val desiredTemperatureValueLabel = new Label {
        text = "-"
    }

    private val measuredTemperatureValueLabel = new Label {
        text = "-"
    }

    private val controlPane = createControlPane()
    controlPane.disable = true

    private val lineChart = createLineChart()

    private def referenceProfileSeries(): XYChart.Series[Number, Number] = {
        lineChart.getData.get(0)
    }

    private def desiredProfileSeries(): XYChart.Series[Number, Number] = {
        lineChart.getData.get(1)
    }

    private def measuredProfileSeries(): XYChart.Series[Number, Number] = {
        lineChart.getData.get(2)
    }

    private def cleanReceivedSeries(): Unit = {
        desiredProfileSeries().getData.clear()
        measuredProfileSeries().getData.clear()
    }

    // main UI
    stage = createStage()

    // TODO
    referenceProfileSeries().node.value.style = "-fx-stroke-width: 1; -fx-stroke: #808080; -fx-stroke-dash-array: 5 10;"

    Communication.messageFromSerial.subscribe { message =>
        Platform.runLater {
            message match {
                case msg: SerialPortActor.Information => {
                    if (msg.message == null) {
                        // do nothing
                    }
                    else {
                        appendTerminalText(msg.message)
                    }
                }
                case msg: SerialPortActor.Error => {
                    appendTerminalText(msg.message)
                }
                case msg: SerialPortActor.ConnectionClosed => {
                    appendTerminalText(msg.message)

                    startStopButton.text = "Start"

                    connectButton.disable = false
                    connectButton.text = "Connect"
                    connectButton.onAction = handle {
                        onConnectHandle()
                    }

                    commandInput.disable = false
                    availablePortsChoiceBox.disable = false

                    commandInput.disable = true
                    controlPane.disable = true
                    desiredTemperatureValueLabel.text = "-"
                    measuredTemperatureValueLabel.text = "-"

                    cleanReceivedSeries()
                }
                case msg: SerialPortActor.ConnectionSuccess => {
                    appendTerminalText("Successfully connected")

                    commandInput.disable = false

                    connectButton.disable = false
                    connectButton.text = "Disconnect"
                    connectButton.onAction = handle {
                        onDisconnectHandle()
                    }

                    controlPane.disable = false
                }
                case msg: SerialPortActor.DataReceived => {
                    processData(msg.data)
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

        val referenceProfile = new XYChart.Series[Number, Number] {
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

        val desiredProfile = new XYChart.Series[Number, Number] {
            name = "Desired"
            data = Seq.empty
        }

        val measuredProfile = new XYChart.Series[Number, Number] {
            name = "Measured"
            data = Seq.empty
        }

        val lineChart = new LineChart[Number, Number](timeAxis, temperatureAxis, ObservableBuffer(
            referenceProfile, desiredProfile, measuredProfile))
        lineChart.setAnimated(false)
        lineChart.setCreateSymbols(false)

        lineChart
    }

    private def createRightPane(): Pane = {
        new VBox {
            spacing = 5
            children = Seq(
                createConnectionPane(),
                new Separator(),
                controlPane
            )
        }
    }

    private def createConnectionPane(): Pane = {
        val portLabel = new Label("Port:") {
            alignmentInParent = Pos.BaselineRight
        }

        connectButton.onAction = handle {
            onConnectHandle()
        }

        new HBox {
            minWidth = 300
            spacing = 5
            children = Seq(portLabel, availablePortsChoiceBox, connectButton)
        }
    }

    private def createControlPane(): Pane = {
        val desiredTemperatureLabel = new Label {
            style = "-fx-font-weight: bold"
            text = "Desired temperature: "
        }

        val measuredTemperatureLabel = new Label {
            style = "-fx-font-weight: bold"
            text = "Measured temperature: "
        }

        val temperatures = new VBox {
            spacing = 5
            children = Seq(
                new HBox {
                    spacing = 5
                    children = Seq(desiredTemperatureLabel, desiredTemperatureValueLabel)
                },
                new HBox {
                    spacing = 5
                    children = Seq(measuredTemperatureLabel, measuredTemperatureValueLabel)
                }
            )
        }

        val pidSpinners = Seq(
            ("P", 10),
            ("I", 20),
            ("D", 30)
        ).zipWithIndex.flatMap { case ((name, initialValue), index) =>
            createPidSpinBox(name, initialValue, index)
        }

        val pidValues = new GridPane {
            hgap = 5
            vgap = 5
            alignmentInParent = Pos.BaselineRight
            children = pidSpinners
        }

        new VBox {
            spacing = 10
            children = Seq(startStopButton, temperatures, pidValues)
        }
    }

    private def createPidSpinBox(name: String, initialValue: Float, rowIndex: Int): Seq[Node] = {
        val label = new Label {
            style = "-fx-font-weight: bold"
            text = s"$name: "
        }

        val spinBox = new Spinner[Double](0, 10000, initialValue) {
            value.addListener(new ChangeListener[Double] {
                def changed(observable: ObservableValue[_ <: Double], oldValue: Double, newValue: Double): Unit = {
                    // TODO
                    println(s"""new value $name $newValue""")
                    //serialPort ! SerialPortActor.SendCommand("")
                }
            })
        }

        GridPane.setConstraints(label, 0, rowIndex, 1, 1)
        GridPane.setConstraints(spinBox, 1, rowIndex, 1, 1)

        Seq(label, spinBox)
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
        Option(availablePortsChoiceBox.getSelectionModel.getSelectedItem) match {
            case Some(portName) => {
                availablePortsChoiceBox.disable = true

                connectButton.disable = true
                connectButton.text = "Connecting..."

                serialPort ! SerialPortActor.Open(portName)
            }
            case _ => {
                appendTerminalText("Error: please select a port before connecting")
            }
        }
    }

    private def onDisconnectHandle(): Unit = {
        serialPort ! SerialPortActor.Close()
    }














    sealed abstract class CommandResult(val code: Int, val expectedLength: Int) {
        def action(bytes: Array[Byte]): Unit
    }

    case object StatsCommandResult extends CommandResult(0xD010, 16) {
        def action(bytes: Array[Byte]): Unit = {
            val timestamp = SerialPortActor.extractFloat(bytes.slice(4, 8))
            val desiredTemperature = SerialPortActor.extractFloat(bytes.slice(8, 12))
            val measuredTemperature = SerialPortActor.extractFloat(bytes.slice(12, 16))

            lineChart.getData.get(1).getData.add(XYChart.Data[Number, Number](timestamp, desiredTemperature))
            lineChart.getData.get(2).getData.add(XYChart.Data[Number, Number](timestamp, measuredTemperature))

            desiredTemperatureValueLabel.text = f"$desiredTemperature%1.2f°C"
            measuredTemperatureValueLabel.text = f"$measuredTemperature%1.2f°C"
        }
    }

    case object UnknownCommandResult extends CommandResult(0xFF01, 4) {
        def action(bytes: Array[Byte]): Unit = {
            appendTerminalText("ERROR: oven does not recognize sent data")
        }
    }

    case object StartAckResult extends CommandResult(0xB010, 4) {
        def action(bytes: Array[Byte]): Unit = {
            appendTerminalText("Started")
        }
    }

    case object StartErrResult extends CommandResult(0xB020, 4) {
        def action(bytes: Array[Byte]): Unit = {
            appendTerminalText("ERROR: unexpected start command")
        }
    }

    case object StopAckResult extends CommandResult(0xB110, 4) {
        def action(bytes: Array[Byte]): Unit = {
            appendTerminalText("Stopped")
        }
    }

    case object StopErrResult extends CommandResult(0xB120, 4) {
        def action(bytes: Array[Byte]): Unit = {
            appendTerminalText("ERROR: unexpected stop command")
        }
    }

    case object ReflowCompleteResult extends CommandResult(0xD020, 4) {
        def action(bytes: Array[Byte]): Unit = {
            appendTerminalText("Reflow complete !")
        }
    }

    val registeredCommands = List(
        StatsCommandResult,
        UnknownCommandResult,
        StartAckResult,
        StartErrResult,
        StopAckResult,
        StopErrResult,
        ReflowCompleteResult
    ).map { command =>
        command.code -> command
    }.toMap

    private def processData(data: ByteString): Unit = {
        val bytes = data.toArray

        val dataStr = SerialPortActor.binaryDump(bytes)

        if (bytes.length >= 4) {
            val code = 0xFFFF & SerialPortActor.extractValue(bytes.slice(2, 4)).getShort

            val codeStr = "0x" + Integer.toHexString(code).toUpperCase

            registeredCommands.get(code) match {
                case Some(command) => {
                    if (bytes.length == command.expectedLength) {
                        command.action(bytes)
                    }
                    else {
                        appendTerminalText(s"""Unexpected length "${bytes.length}" for code ${codeStr}, ${command.expectedLength} bytes needed.""")
                    }
                }
                case _ => {
                    appendTerminalText(s"""Unknown code ($codeStr) received in: $dataStr.""")
                }
            }
        }
        else {
            appendTerminalText(s"""Data received should at least contains 4 bytes: $dataStr.""")
        }
    }
}
