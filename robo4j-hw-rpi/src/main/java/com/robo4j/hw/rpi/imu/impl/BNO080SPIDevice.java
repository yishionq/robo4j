/*
 * Copyright (c) 2014, 2019, Marcus Hirt, Miroslav Wengner
 *
 * Robo4J is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Robo4J is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Robo4J. If not, see <http://www.gnu.org/licenses/>.
 */

package com.robo4j.hw.rpi.imu.impl;

import com.pi4j.io.gpio.GpioController;
import com.pi4j.io.gpio.GpioFactory;
import com.pi4j.io.gpio.GpioPinDigitalInput;
import com.pi4j.io.gpio.GpioPinDigitalOutput;
import com.pi4j.io.gpio.Pin;
import com.pi4j.io.gpio.PinPullResistance;
import com.pi4j.io.gpio.PinState;
import com.pi4j.io.gpio.RaspiPin;
import com.pi4j.io.spi.SpiChannel;
import com.pi4j.io.spi.SpiDevice;
import com.pi4j.io.spi.SpiMode;
import com.pi4j.io.spi.impl.SpiDeviceImpl;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Abstraction for a BNO080 absolute orientation device.
 *
 * <p>
 * Channel 0: the SHTP command channel Channel 1: executable Channel 2: sensor
 * hub control channel Channel 3: input sensor reports (non-wake, not gyroRV)
 * Channel 4: wake input sensor reports (for sensors configured as wake
 * upsensors) Channel 5: gyro rotation vector
 * </p>
 *
 * https://github.com/sparkfun/SparkFun_BNO080_Arduino_Library/blob/master/src/SparkFun_BNO080_Arduino_Library.cpp
 *
 * RPi/Pi4j pins https://pi4j.com/1.2/pins/model-3b-rev1.html
 *
 * @author Marcus Hirt (@hirt)
 * @author Miroslav Wengner (@miragemiko)
 */
public class BNO080SPIDevice {

	private static final class ShtpPacket {
		private final int[] header = new int[SHTP_HEADER_SIZE];
		private int[] body;

		ShtpPacket(int size) {
			this.body = new int[size];
		}

		void addHeader(int... header) {
			if (header.length != this.header.length) {
				System.out.println("ShtpPacket: wrong header");
			} else {
				this.header[0] = header[0];
				this.header[1] = header[1];
				this.header[2] = header[2];
				this.header[3] = header[3];
			}

		}

		void addBody(int pos, int element) {
			body[pos] = element;
		}

		int[] getHeader() {
			return header;
		}

		int[] getBody() {
			return body;
		}

		boolean dataAvailable() {
			return body.length > 0;
		}
	}

	private static final class PacketBodyBuilder {
		private byte[] body;
		private final AtomicInteger counter = new AtomicInteger();

		PacketBodyBuilder(int size) {
			body = new byte[size];
		}

		PacketBodyBuilder addElement(int value) {
			this.body[counter.getAndIncrement()] = (byte) value;
			return this;
		}

		byte[] build() {
			return body;
		}
	}

	public static final SpiMode DEFAULT_SPI_MODE = SpiMode.MODE_3;
	public static final int DEFAULT_SPI_SPEED = 3000000; // 3MHz maximum SPI speed
	public static final short CHANNEL_COUNT = 6; // BNO080 supports 6 channels

	// Registers
	private static final byte CHANNEL_COMMAND = 0;
	private static final byte CHANNEL_EXECUTABLE = 1;
	private static final byte CHANNEL_CONTROL = 2;
	private static final byte CHANNEL_REPORTS = 3;
	private static final byte CHANNEL_WAKE_REPORTS = 4;
	private static final byte CHANNEL_GYRO = 5;

	// All the ways we can configure or talk to the BNO080, figure 34, page 36
	// reference manual
	// These are used for low level communication with the sensor, on channel 2
	private static final int SHTP_REPORT_COMMAND_RESPONSE = 0xF1;
	private static final int SHTP_REPORT_COMMAND_REQUEST = 0xF2;
	private static final int SHTP_REPORT_FRS_READ_RESPONSE = 0xF3;
	private static final int SHTP_REPORT_FRS_READ_REQUEST = 0xF4;
	private static final int SHTP_REPORT_PRODUCT_ID_RESPONSE = 0xF8;
	private static final int SHTP_REPORT_PRODUCT_ID_REQUEST = 0xF9;
	private static final int SHTP_REPORT_BASE_TIMESTAMP = 0xFB;
	private static final int SHTP_REPORT_SET_FEATURE_COMMAND = 0xFD;

	// All the different sensors and features we can get reports from
	// These are used when enabling a given sensor
	private static final int SENSOR_REPORTID_ACCELEROMETER = 0x01;
	private static final int SENSOR_REPORTID_GYROSCOPE = 0x02;
	private static final int SENSOR_REPORTID_MAGNETIC_FIELD = 0x03;
	private static final int SENSOR_REPORTID_LINEAR_ACCELERATION = 0x04;
	private static final int SENSOR_REPORTID_ROTATION_VECTOR = 0x05;
	private static final int SENSOR_REPORTID_GRAVITY = 0x06;
	private static final int SENSOR_REPORTID_GAME_ROTATION_VECTOR = 0x08;
	private static final int SENSOR_REPORTID_GEOMAGNETIC_ROTATION_VECTOR = 0x09;
	private static final int SENSOR_REPORTID_TAP_DETECTOR = 0x10;
	private static final int SENSOR_REPORTID_STEP_COUNTER = 0x11;
	private static final int SENSOR_REPORTID_STABILITY_CLASSIFIER = 0x13;
	private static final int SENSOR_REPORTID_PERSONAL_ACTIVITY_CLASSIFIER = 0x1E;

	// Record IDs from figure 29, page 29 reference manual
	// These are used to begin the metadata for each sensor type
	private static final int FRS_RECORDID_ACCELEROMETER = 0xE302;
	private static final int FRS_RECORDID_GYROSCOPE_CALIBRATED = 0xE306;
	private static final int FRS_RECORDID_MAGNETIC_FIELD_CALIBRATED = 0xE309;
	private static final int FRS_RECORDID_ROTATION_VECTOR = 0xE30B;

	// Command IDs from section 6.4, page 42
	// These are used to calibrate, initialize, set orientation, tare etc the sensor
	private static final int COMMAND_ERRORS = 1;
	private static final int COMMAND_COUNTER = 2;
	private static final int COMMAND_TARE = 3;
	private static final int COMMAND_INITIALIZE = 4;
	private static final int COMMAND_DCD = 6;
	private static final int COMMAND_ME_CALIBRATE = 7;
	private static final int COMMAND_DCD_PERIOD_SAVE = 9;
	private static final int COMMAND_OSCILLATOR = 10;
	private static final int COMMAND_CLEAR_DCD = 11;

	private static final int CALIBRATE_ACCEL = 0;
	private static final int CALIBRATE_GYRO = 1;
	private static final int CALIBRATE_MAG = 2;
	private static final int CALIBRATE_PLANAR_ACCEL = 3;
	private static final int CALIBRATE_ACCEL_GYRO_MAG = 4;
	private static final int CALIBRATE_STOP = 5;

	private static final int MAX_PACKET_SIZE = 255; // Packets can be up to 32k but we don't have that much RAM.
	private static final int MAX_METADATA_SIZE = 9; // This is in words. There can be many but we mostly only care about
													// the first 9 (Qs, range, etc)
	private static final int SHTP_HEADER_SIZE = 4;

	// These Q values are defined in the datasheet but can also be obtained by
	// querying the meta data records
	// See the read metadata example for more info
	private static int rotationVector_Q1 = 14;
	private static int accelerometer_Q1 = 8;
	private static int linear_accelerometer_Q1 = 8;
	private static int gyro_Q1 = 9;
	private static int magnetometer_Q1 = 4;

	private SpiDevice spiDevice;
	private GpioController gpio;
	private GpioPinDigitalInput intGpio;
	private GpioPinDigitalOutput wakeGpio;
	private GpioPinDigitalOutput rstGpio;
	private GpioPinDigitalOutput csGpio; // select slave SS = chip select CS
	private int[] sequenceByChannel = new int[CHANNEL_COUNT];

	// unit8_t
	private int stabilityClassifier;
	private int activityClassifier;
	private int[] activityConfidences = new int[9]; // Array that store the confidences of the 9 possible activities
	private int calibrationStatus; // Byte R0 of ME Calibration Response

	// uint16_t
	private int rawAccelX, rawAccelY, rawAccelZ, accelAccuracy;
	private int rawLinAccelX, rawLinAccelY, rawLinAccelZ, accelLinAccuracy;
	private int rawGyroX, rawGyroY, rawGyroZ, gyroAccuracy;
	private int rawMagX, rawMagY, rawMagZ, magAccuracy;
	private int rawQuatI, rawQuatJ, rawQuatK, rawQuatReal, rawQuatRadianAccuracy, quatAccuracy;
	private int stepCount;

	// uint32_t
	private int timeStamp;
	private long startTime;

	public BNO080SPIDevice() throws IOException, InterruptedException {
		this(SpiChannel.CS0, DEFAULT_SPI_SPEED, DEFAULT_SPI_MODE);
	}

	public BNO080SPIDevice(SpiChannel spiChannel, int speed, SpiMode mode) throws IOException {
		spiDevice = new SpiDeviceImpl(spiChannel, speed, mode);
		gpio = GpioFactory.getInstance();
	}

	/**
	 * Configure SPI default configuration
	 *
	 * @throws IOException
	 *             exception
	 * @throws InterruptedException
	 *             exception
	 */
	public boolean configureSpiPins() throws IOException, InterruptedException {
		return configureSpiPins(RaspiPin.GPIO_00, RaspiPin.GPIO_25, RaspiPin.GPIO_02, RaspiPin.GPIO_03);
	}

	/**
	 * Configure SPI by Pins
	 *
	 * @param wake
	 *            Active low, Used to wake the processor from a sleep mode.
	 * @param cs
	 *            Chip select, active low, used as chip select/slave select on SPI
	 * @param rst
	 *            Reset signal, active low, pull low to reset IC
	 * @param inter
	 *            Interrupt, active low, pulls low when the BNO080 is ready for
	 *            communication.
	 * @return process done
	 * @throws IOException
	 *             exception
	 * @throws InterruptedException
	 *             exception
	 */
	private boolean configureSpiPins(Pin wake, Pin cs, Pin rst, Pin inter) throws IOException, InterruptedException {
		System.out.println(String.format("configurePins: wak=%s, cs=%s, rst=%s, inter=%s", wake, cs, rst, inter));
		intGpio = gpio.provisionDigitalInputPin(inter, "INT", PinPullResistance.PULL_UP);
		csGpio = gpio.provisionDigitalOutputPin(cs, "CS");
		wakeGpio = gpio.provisionDigitalOutputPin(wake, "WAKE");
		rstGpio = gpio.provisionDigitalOutputPin(rst, "RST");

		csGpio.setState(PinState.HIGH); // Deselect BNO080

		// Configure the BNO080 for SPI communication
		wakeGpio.setState(PinState.HIGH); // Before boot up the
		rstGpio.setState(PinState.LOW); // Reset BNO080
		TimeUnit.MILLISECONDS.sleep(2); // Min length not specified in datasheet?
		rstGpio.setState(PinState.HIGH); // Bring out of reset

		return true;
	}

	private void waitForInterrupt(String message) throws InterruptedException, IOException {
		if (waitForSPI()) {
			// Wait for assertion of INT before reading advert message.
			receivePacket();
		} else {
			System.err.println(message);
		}
	}

	public boolean beginSPI() throws InterruptedException, IOException {
		// Wait for first assertion of INT before using WAK pin. Can take ~104ms
		boolean state = waitForSPI();
		System.out.println("beginSPI state: " + state);

		/*
		 * At system startup, the hub must send its full advertisement message (see 5.2
		 * and 5.3) to the host. It must not send any other data until this step is
		 * complete. When BNO080 first boots it broadcasts big startup packet Read it
		 * and dump it
		 */
		waitForInterrupt("beginSPI: system startup");

		/*
		 * The BNO080 will then transmit an unsolicited Initialize Response (see
		 * 6.4.5.2) Read it and dump it
		 */
		waitForInterrupt("beginSPI: BNO080 unsolicited response");

		// Check communication with device
		// bytes: Request the product ID and reset info, Reserved
		byte[] reportRequestPacket = { (byte) SHTP_REPORT_PRODUCT_ID_REQUEST, 0 };
		// Transmit packet on channel 2, 2 bytes
		sendPacket(CHANNEL_CONTROL, reportRequestPacket, "beginSPI:CHANNEL_CONTROL:SHTP_REPORT_PRODUCT_ID_REQUEST");

		// Now we wait for response
		waitForSPI();

		ShtpPacket shtpPacket = receivePacket();
		if (shtpPacket.dataAvailable()) {
			int[] receivedBody = shtpPacket.getBody();
			if (receivedBody[0] == SHTP_REPORT_PRODUCT_ID_RESPONSE) {
				printShtpPacketPart("beginSPI, body:", receivedBody);
				return true;
			} else {
				System.out.println("beginSPI: received not valid response ");
			}
		} else {
			System.out.println("beginSPI: no packet received");
		}
		return false;

	}

	public void enableRotationVector(int timeBetweenReports) throws InterruptedException, IOException {
		setFeatureCommand(SENSOR_REPORTID_ROTATION_VECTOR, timeBetweenReports, 0);
	}

	public void enableLinearAccelerometer(int timeBetweenReports) throws InterruptedException, IOException {
		setFeatureCommand(SENSOR_REPORTID_LINEAR_ACCELERATION, timeBetweenReports, 0);
	}

	/**
	 * Rotation vector example
	 * 
	 * 
	 * @param readings
	 *            number of readings
	 * @param delay
	 *            delay in ms
	 * @throws IOException
	 *             exception
	 * @throws InterruptedException
	 *             exception
	 */
	public void startRotationVector(int readings, int delay) throws IOException, InterruptedException {
		startTime = System.currentTimeMillis();
		long measurements = 0;
		for (int i = 0; i < readings; i++) {
			System.out.println(String.format("startRotationVector measurements: %d, readings: %d ", measurements, i));
			TimeUnit.MICROSECONDS.sleep(delay);
			ShtpPacket packet = dataAvailable();
			if (packet.dataAvailable()) {

				float quatI = getQuatI();
				float quatJ = getQuatJ();
				float quatK = getQuatK();
				float quatReal = getQuatReal();
				float quatRadianAccuracy = getQuatRadianAccuracy();
				measurements++;

				float frequency = measurements / ((System.currentTimeMillis() - startTime) / 1000f);

				System.out.print(String.format("enableRotationVector quatI: %.2f,", quatI));
				System.out.print(String.format("enableRotationVector quatJ: %.2f,", quatJ));
				System.out.print(String.format("enableRotationVector quatK: %.2f,", quatK));
				System.out.print(String.format("enableRotationVector quatReal: %.2f,", quatReal));
				System.out.print(String.format("enableRotationVector quatRadianAccuracy: %.2f,", quatRadianAccuracy));
				System.out.print(String.format("enableRotationVector measurement: %f Hz", frequency));
				System.out.println();
			}
		}
	}

	private float getQuatI() {
		return qToFloat(rawQuatI, rotationVector_Q1);
	}

	private float getQuatJ() {
		return qToFloat(rawQuatJ, rotationVector_Q1);
	}

	private float getQuatK() {
		return qToFloat(rawQuatK, rotationVector_Q1);
	}

	private float getQuatReal() {
		return qToFloat(rawQuatRadianAccuracy, rotationVector_Q1);
	}

	private float getQuatRadianAccuracy() {
		return qToFloat(rawQuatRadianAccuracy, rotationVector_Q1);
	}

	/**
	 * Given a register value and a Q point, convert to float See
	 * https://en.wikipedia.org/wiki/Q_(number_format)
	 *
	 * @param fixedPointValue
	 *            fixed point value
	 * @param qPoint
	 *            q point
	 * @return float value
	 */
	private float qToFloat(int fixedPointValue, int qPoint) {
		float qFloat = fixedPointValue & 0xFFFF;
		qFloat *= Math.pow(2, (qPoint & 0xFF) * -1);
		return qFloat;
	}

	/**
	 * Updates the latest variables if possible
	 * 
	 * @return false if new readings are not available
	 * @throws IOException
	 *             exception
	 * @throws InterruptedException
	 *             exception
	 */
	private ShtpPacket dataAvailable() throws IOException, InterruptedException {
		if (intGpio.isHigh()) {
			return new ShtpPacket(0);
		}

		// TimeUnit.MILLISECONDS.sleep(2);
		ShtpPacket receivePacket = receivePacket();
		if (receivePacket.dataAvailable()) {
			// Check to see if this packet is a sensor reporting its data to us
			if (receivePacket.getHeader()[2] == CHANNEL_REPORTS
					&& receivePacket.getBody()[0] == SHTP_REPORT_BASE_TIMESTAMP) {
				parseInputReport(receivePacket); // This will update the rawAccelX, etc variables depending on which
				return receivePacket;
			} else if (receivePacket.getHeader()[2] == CHANNEL_CONTROL) {
				parseCommandReport(receivePacket); // This will update responses to commands, calibrationStatus, etc.
				return receivePacket;
			}
		}

		return new ShtpPacket(0);
	}

	/**
	 * Unit responds with packet that contains the following
	 */
	// shtpHeader[0:3]: First, a 4 byte header
	// shtpData[0]: The Report ID
	// shtpData[1]: Sequence number (See 6.5.18.2)
	// shtpData[2]: Command
	// shtpData[3]: Command Sequence Number
	// shtpData[4]: Response Sequence Number
	// shtpData[5 + 0]: R0
	// shtpData[5 + 1]: R1
	// shtpData[5 + 2]: R2
	// shtpData[5 + 3]: R3
	// shtpData[5 + 4]: R4
	// shtpData[5 + 5]: R5
	// shtpData[5 + 6]: R6
	// shtpData[5 + 7]: R7
	// shtpData[5 + 8]: R8
	private void parseCommandReport(ShtpPacket packet) {
		int[] shtpData = packet.getBody();
		if ((shtpData[0] & 0xFF) == SHTP_REPORT_COMMAND_RESPONSE) {
			System.out.println("parseCommandReport: commandResponse");
			// The BNO080 responds with this report to command requests. It's up to use to
			// remember which command we issued.
			int command = shtpData[2] & 0xFF; // This is the Command byte of the response

			if (command == COMMAND_ME_CALIBRATE) {
				calibrationStatus = shtpData[5] & 0xFF; // R0 - Status (0 = success, non-zero = fail)
			}
		} else {
			// This sensor report ID is unhandled.
			System.out.println("parseCommandReport: This sensor report ID is unhandled");
		}

	}

	/**
	 * Unit responds with packet that contains the following: shtpHeader[0:3]:
	 * First, a 4 byte header shtpData[0:4]: Then a 5 byte timestamp of microsecond
	 * clicks since reading was taken shtpData[5 + 0]: Then a feature report ID
	 * (0x01 for Accel, 0x05 for Rotation Vector) shtpData[5 + 1]: Sequence number
	 * (See 6.5.18.2) shtpData[5 + 2]: Status shtpData[3]: Delay shtpData[4:5]:
	 * i/accel x/gyro x/etc shtpData[6:7]: j/accel y/gyro y/etc shtpData[8:9]:
	 * k/accel z/gyro z/etc shtpData[10:11]: real/gyro temp/etc shtpData[12:13]:
	 * Accuracy estimate
	 */
	private void parseInputReport(ShtpPacket packet) {
		// Calculate the number of data bytes in this packet

		int[] shtpHeader = packet.getHeader();
		int[] shtpData = packet.getBody();

		int dataLength = calculateNumberOfBytesInPacket(shtpHeader[1], shtpHeader[0]);
		dataLength -= SHTP_HEADER_SIZE;

		timeStamp = (shtpData[4] << (8 * 3)) | (shtpData[3] << (8 * 2)) | (shtpData[2] << (8 * 1))
				| (shtpData[1] << (8 * 0));
		int status = (shtpData[5 + 2] & 0x03) & 0xFF; // Get status bits
		int data1 = ((shtpData[5 + 5] << 8) & 0xFFFF) | shtpData[5 + 4] & 0xFF;
		int data2 = (shtpData[5 + 7] << 8 & 0xFFFF | (shtpData[5 + 6]) & 0xFF);
		int data3 = (shtpData[5 + 9] << 8 & 0xFFFF) | (shtpData[5 + 8] & 0xFF);
		int data4 = 0;
		int data5 = 0;

		if (dataLength - 5 > 9) {
			data4 = (shtpData[5 + 11] & 0xFFFF) << 8 | shtpData[5 + 10] & 0xFF;
		}
		if (dataLength - 5 > 11) {
			data5 = (shtpData[5 + 13] & 0xFFFF) << 8 | shtpData[5 + 12] & 0xFF;
		}

		if ((shtpData[5] & 0xFF) == SENSOR_REPORTID_ACCELEROMETER) {
			accelAccuracy = status & 0xFFFF;
			rawAccelX = data1 & 0xFFFF;
			rawAccelY = data2 & 0xFFFF;
			rawAccelZ = data3 & 0xFFFF;
		} else if ((shtpData[5] & 0xFF) == SENSOR_REPORTID_LINEAR_ACCELERATION) {
			accelLinAccuracy = status & 0xFFFF;
			rawLinAccelX = data1 & 0xFFFF;
			rawLinAccelY = data2 & 0xFFFF;
			rawLinAccelZ = data3 & 0xFFFF;
		} else if ((shtpData[5] & 0xFF) == SENSOR_REPORTID_GYROSCOPE) {
			gyroAccuracy = status & 0xFFFF;
			rawGyroX = data1 & 0xFFFF;
			rawGyroY = data2 & 0xFFFF;
			rawGyroZ = data3 & 0xFFFF;
		} else if (shtpData[5] == SENSOR_REPORTID_MAGNETIC_FIELD) {
			magAccuracy = status & 0xFFFF;
			rawMagX = data1 & 0xFFFF;
			rawMagY = data2 & 0xFFFF;
			rawMagZ = data3 & 0xFFFF;
		} else if ((shtpData[5] & 0xFF) == SENSOR_REPORTID_ROTATION_VECTOR
				|| (shtpData[5] & 0xFF) == SENSOR_REPORTID_GAME_ROTATION_VECTOR) {
			quatAccuracy = status & 0xFFFF;
			rawQuatI = data1 & 0xFFFF;
			rawQuatJ = data2 & 0xFFFF;
			rawQuatK = data3 & 0xFFFF;
			rawQuatReal = data4 & 0xFFFF;
			rawQuatRadianAccuracy = data5 & 0xFFFF; // Only available on rotation vector, not game rot vector
		} else if ((shtpData[5] & 0xFF) == SENSOR_REPORTID_STEP_COUNTER) {
			stepCount = data3 & 0xFFFF; // Bytes 8/9
		} else if ((shtpData[5] & 0xFF) == SENSOR_REPORTID_STABILITY_CLASSIFIER) {
			stabilityClassifier = shtpData[5 + 4] & 0xFF; // Byte 4 only
		} else if ((shtpData[5] & 0xFF) == SENSOR_REPORTID_PERSONAL_ACTIVITY_CLASSIFIER) {
			activityClassifier = shtpData[5 + 5] & 0xFF; // Most likely state

			// Load activity classification confidences into the array
			for (int x = 0; x < 9; x++) // Hardcoded to max of 9. TODO - bring in array size
				activityConfidences[x] = shtpData[5 + 6 + x] & 0xFF; // 5 bytes of timestamp, byte 6 is first confidence
																		// byte
		} else if (shtpData[5] == SHTP_REPORT_COMMAND_RESPONSE) {
			// The BNO080 responds with this report to command requests. It's up to use to
			// remember which command we issued.
			int command = shtpData[5 + 2] & 0xFF; // This is the Command byte of the response

			if (command == COMMAND_ME_CALIBRATE) {
				calibrationStatus = shtpData[5 + 5] & 0xFF; // R0 - Status (0 = success, non-zero = fail)
			}
		} else {
			System.out.println("parseInputReport: This sensor report ID is unhandled");
		}

	}

	/**
	 * Given a sensor's report ID, this tells the BNO080 to begin reporting the
	 * values Also sets the specific config word. Useful for personal activity
	 * classifier
	 *
	 *
	 * @param reportId
	 *            - report id uint8_t
	 * @param timeBetweenReports
	 *            - time between reports uint16_t
	 * @param sc
	 *            - contains specific config (uint32_t)
	 * @throws InterruptedException
	 *             exception
	 * @throws IOException
	 *             exception
	 */
	private void setFeatureCommand(int reportId, int timeBetweenReports, int specificConfig)
			throws InterruptedException, IOException {

		long microsBetweenReports = timeBetweenReports * 1000L;

		byte[] packetBody = new PacketBodyBuilder(17).addElement(SHTP_REPORT_SET_FEATURE_COMMAND) // Set feature
																									// command.
				.addElement(reportId) // Feature Report ID. 0x01 = Accelerometer, 0x05 = Rotation vector
				.addElement(0) // Feature flags
				.addElement(0) // Change sensitivity (LSB)
				.addElement(0) // Change sensitivity (MSB)
				.addElement((int) (microsBetweenReports & 0xFF)) // Report interval (LSB) in microseconds. 0x7A120=500ms
				.addElement((int) (microsBetweenReports >> 8) & 0xFF) // Report interval
				.addElement((int) (microsBetweenReports >> 16) & 0xFF) // Report interval
				.addElement((int) (microsBetweenReports >> 24) & 0xFF) // Report interval (MSB)
				.addElement(0) // Batch Interval (LSB)
				.addElement(0) // Batch Interval
				.addElement(0) // Batch Interval
				.addElement(0) // Batch Interval (MSB)
				.addElement(specificConfig & 0xFF) // Sensor-specific config (LSB)
				.addElement((specificConfig >> 8) & 0xFF) // Sensor-specific config
				.addElement((specificConfig >> 16) & 0xFF) // Sensor-specific config
				.addElement((specificConfig >> 24) & 0xFF) // Sensor-specific config (MSB)
				.build();

		// Transmit packet on channel 2, 17 bytes
		sendPacket(CHANNEL_CONTROL, packetBody, "setFeatureCommand");
	}

	/**
	 * Blocking wait for BNO080 to assert (pull low) the INT pin indicating it's
	 * ready for comm. Can take more than 200ms after a hardware reset
	 */
	private boolean waitForSPI() throws IOException, InterruptedException {
		int counter = 0;
		for (int i = 0; i < MAX_PACKET_SIZE; i++) {
			if (intGpio.isLow()) {
				return true;
			} else {
				counter++;
			}
			TimeUnit.MILLISECONDS.sleep(1);
		}
		System.out.println("waitForSPI: ERROR counter: " + counter);
		return false;
	}

	private void printArray(String message, int[] array) {
		System.out.print("printArray: " + message);
		for (int i = 0; i < array.length; i++) {
			System.out.print(" " + (array[i] & 0xFF) + ",");
		}
		System.out.print("\n");
	}

	private void printArray(String message, byte[] array) {
		System.out.print("printArray: " + message);
		for (int i = 0; i < array.length; i++) {
			System.out.print(" " + Integer.toHexString(array[i] & 0xFF) + ",");
		}
		System.out.print("\n");
	}

	private boolean sendPacket(byte channelNumber, byte[] data, String message)
			throws InterruptedException, IOException {
		System.out.println(String.format("sendPacket from: %s channelNumber: %d", message, channelNumber));
		int packetLength = (data.length + SHTP_HEADER_SIZE) & 0xFF; // Add four bytes for the header

		// Wait for BNO080 to indicate it is available for communication
		if (!waitForSPI()) {
			System.out.println("sendPacket SPI not available for communication");
			return false;
		}

		// BNO080 has max CLK of 3MHz, MSB first,
		// The BNO080 uses CPOL = 1 and CPHA = 1. This is mode3
		csGpio.setState(PinState.LOW);
		byte[] spiHeader = createSpiHeader(packetLength, channelNumber);

		for (int i = 0; i < spiHeader.length; i++) {
			spiDevice.write(spiHeader[i]);
		}

		printArray("sendPacket data: ", data);
		for (int i = 0; i < data.length; i++) {
			spiDevice.write(data[i]);
		}
		csGpio.setState(PinState.HIGH);
		return true;
	}

	private byte[] createSpiHeader(int packetLength, byte channelNumber) {
		byte[] header = new byte[SHTP_HEADER_SIZE];
		header[0] = (byte) (packetLength & 0xFF); // LSB
		header[1] = (byte) (packetLength >> 8); // MSB
		header[2] = (byte) (channelNumber & 0xFF); // Channel Number
		// Send the sequence number, increments with each packet sent, different counter
		// for each channel
		header[3] = (byte) (0xFF & sequenceByChannel[channelNumber]++); // Sequence number
		return header;
	}

	private void printShtpPacketPart(String prefix, int[] data) {
		switch (data[0]) {
		case SHTP_REPORT_PRODUCT_ID_RESPONSE:
			System.out.println("printShtpPacketPart:" + prefix + ":SHTP_REPORT_PRODUCT_ID_RESPONSE: "
					+ Integer.toHexString(data[0]));
			break;
		default:
			System.out.println("printShtpPacketPart:" + prefix + ":not implemented: " + Integer.toHexString(data[0]));
		}
		for (int i = 0; i < data.length; i++) {
			System.out.println("printShtpPacketPart" + prefix + "::[" + i + "]:" + Integer.toHexString(data[i]));
		}

	}

	private ShtpPacket receivePacket() throws IOException, InterruptedException {
		// we dont have INT
		if (intGpio.isHigh()) {
			System.out.println("receivePacket: INTERRUPT: data are not available");
			return new ShtpPacket(0);
		}

		// Get first four bytes to find out how much data we need to read
		csGpio.setState(PinState.LOW);

		// Get the first four bytes, aka the packet header
		int packetLSB = spiDevice.write((byte) 0xFF)[0];
		int packetMSB = spiDevice.write((byte) 0xFF)[0];
		int channelNumber = spiDevice.write((byte) 0xFF)[0];
		int sequenceNumber = spiDevice.write((byte) 0xFF)[0]; // Not sure if we need to store this or not

		// Calculate the number of data bytes in this packet
		// int dataLength = (packetMSB << 8 | packetLSB);
		// dataLength &= ~(1 << 15); // Clear the MSbit.
		// int dataLength = ((packetMSB & 0xFFFF) << 8) | packetLSB ;
		int dataLength = calculateNumberOfBytesInPacket(packetMSB, packetLSB);
		// This bit indicates if this package is a continuation of the last. Ignore it
		// for now.
		if (dataLength == 0) {
			return new ShtpPacket(0);
		}
		dataLength -= SHTP_HEADER_SIZE;
		System.out.println("receivedPacket: dataLength=" + dataLength);
		if (dataLength <= 0) {
			return new ShtpPacket(0);
		}

		ShtpPacket shtpPacket = new ShtpPacket(dataLength);
		shtpPacket.addHeader(packetLSB, packetMSB, channelNumber, sequenceNumber);

		// Read incoming data into the shtpData array
		for (int i = 0; i < dataLength; i++) {
			byte[] inArray = spiDevice.write((byte) 0xFF);
			byte incoming = inArray[0];
			if (i < MAX_PACKET_SIZE) {
				shtpPacket.addBody(i, incoming & 0xFF);
			}
		}

		/*
		 * looks like we need to flush the header
		 */
		for (int i = 0; i <= SHTP_HEADER_SIZE; i++) {
			byte flushByte = spiDevice.write((byte) 0xFF)[0];
			System.out.println("receivedPacket: flush" + Integer.toHexString(flushByte));
		}
		csGpio.setState(PinState.HIGH); // Release BNO080

		printShtpPacketPart("receivePacketChannelSequenceNumbers", sequenceByChannel);
		printShtpPacketPart("receivePacketHeader", shtpPacket.getHeader());
		printShtpPacketPart("receivePacketBody", shtpPacket.getBody());

		return shtpPacket; // we are done

	}

	private int calculateNumberOfBytesInPacket(int packetMSB, int packetLSB) {
		// Calculate the number of data bytes in this packet
		int dataLength = (0xFFFF & ((packetMSB & 0xFF) << 8) | packetLSB) & 0xFFFF;
		dataLength &= ~(1 << 15) & 0xFF; // Clear the MSbit.
		return dataLength;
	}
}
