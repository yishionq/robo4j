/*
 * Copyright (c) 2019, Marcus Hirt, Miroslav Wengner
 * 
 * Robo4J is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Robo4J is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Robo4J. If not, see <http://www.gnu.org/licenses/>.
 */
package com.robo4j.hw.rpi.serial.ydlidar;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.pi4j.io.serial.Serial;
import com.pi4j.io.serial.SerialFactory;
import com.robo4j.hw.rpi.serial.SerialDeviceDescriptor;
import com.robo4j.hw.rpi.serial.SerialUtil;
import com.robo4j.hw.rpi.serial.ydlidar.DataHeader.PacketType;
import com.robo4j.hw.rpi.serial.ydlidar.HealthInfo.HealthStatus;
import com.robo4j.hw.rpi.serial.ydlidar.ResponseHeader.ResponseMode;
import com.robo4j.hw.rpi.serial.ydlidar.ResponseHeader.ResponseType;
import com.robo4j.math.geometry.Point2f;
import com.robo4j.math.geometry.ScanResult2D;
import com.robo4j.math.geometry.impl.ScanResultImpl;
import com.robo4j.math.jfr.ScanEvent;

/**
 * Driver for the ydlidar device.
 * 
 * <p>
 * To play with it directly from the command line, and ensure that it is happy,
 * you can first set up the device to use the correct settings, e.g. (if on
 * /dev/ttyUSB0):
 * <p>
 * stty -F /dev/ttyUSB0 230400 cs8 -cstopb -parenb
 * <p>
 * Next you can cat the device to list what it is saying, for example cat to
 * file:
 * <p>
 * cat /dev/ttyUSB0 > mytest
 * <p>
 * Of course, to make it say something, you will need to send it a command, for
 * example:
 * <p>
 * echo -en "\xA5\x90" > /dev/ttyUSB0
 * 
 * @author Marcus Hirt (@hirt)
 * @author Miro Wengner (@miragemiko)
 */
public class YDLidarDevice {
	private static final Logger LOGGER = Logger.getLogger(YDLidarDevice.class.getClass().getName());
	private static final int DEFAULT_SERIAL_TIMEOUT = 800;

	public static final String SERIAL_PORT_AUTO = "auto";

	/**
	 * Vendor ID for the CP2102 USB to UART Bridge Controller that is included
	 * with the YDLidar device. Used for auto detection.
	 */
	private static final String VENDOR_ID = "10c4";

	/**
	 * The product ID for the CP2102 USB to UART Bridge Controller that is
	 * included with the YDLidar device. Used for auto detection.
	 */
	private static final String PRODUCT_ID = "ea60";

	// This should be calculated later - right now assuming defaults (9kHz
	// sample frequency, 7Hz rotation rate).
	private static final float ANGULAR_RESOLUTION = (float) Math.toRadians(360.0 / (9000.0 / 7.0));

	private static final int CMDFLAG_HAS_PAYLOAD = 0x80;
	private static final byte CMD_SYNC_BYTE = (byte) 0xA5;
	private static final int BAUD_RATE = 230400;

	private final Serial serial;
	private final String serialPort;
	private final ScanReceiver receiver;

	private volatile boolean isScanning;

	public enum IdleMode {
		NORMAL, LOW_POWER
	}

	public enum RangingFrequency {
		FOUR_KHZ((byte) 0x00, 4), EIGHT_KHZ((byte) 0x01, 8), NINE_KHZ((byte) 0x02, 9);

		byte frequencyCode;
		int frequency;

		RangingFrequency(byte frequencyCode, int frequency) {
			this.frequencyCode = frequencyCode;
			this.frequency = frequency;
		}

		public byte getFrequencyCode() {
			return frequencyCode;
		}

		/**
		 * Returns the RangingFrequency from the ydlidar specific frequency
		 * code.
		 * 
		 * @param frequencyCode
		 *            the ydlidar specific frequency code for which to get the
		 *            {@link RangingFrequency}.
		 * @return the {@link RangingFrequency} or null, if no matching
		 *         {@link RangingFrequency} could be found.∫
		 */
		public static RangingFrequency fromFrequencyCode(int frequencyCode) {
			for (RangingFrequency frequency : values()) {
				if (frequency.getFrequencyCode() == frequencyCode) {
					return frequency;
				}
			}
			return null;
		}
	}

	private enum RetrieverState {
		NORMAL, ENDING, ENDED
	}

	public class DataRetriever implements Runnable {
		private final List<Point2f> survivors = new ArrayList<>();
		private RetrieverState state = RetrieverState.NORMAL;

		@Override
		public void run() {
			while (isScanning) {
				// TODO(Marcus/18 aug. 2019): Calculate the angular resolution
				// properly.
				ScanResultImpl scanResult = new ScanResultImpl(ANGULAR_RESOLUTION);
				ScanEvent event = new ScanEvent(scanResult.getScanID(), "ydlidar 360");
				if (!survivors.isEmpty()) {
					scanResult.addAll(survivors);
					survivors.clear();
				}
				while (true) {
					try {
						DataHeader header = readDataHeader(DEFAULT_SERIAL_TIMEOUT * 4);
						if (!header.isValid()) {
							LOGGER.log(Level.SEVERE, "Got invalid header - stopping scanner");
							stopScanning();
							return;
						}
						byte[] data = readData(header, DEFAULT_SERIAL_TIMEOUT);

						scanResult.addAll(calculatePoints(header, data));
						if (state == RetrieverState.ENDED) {
							break;
						}
					} catch (IllegalStateException | IOException | InterruptedException | TimeoutException e) {
						LOGGER.log(Level.SEVERE, "Failed to read data from the ydlidar - stopping scanner", e);
						stopScanning();
						return;
					}
				}
				state = RetrieverState.NORMAL;
				if (scanResult.getPoints().size() != 0) {
					receiver.onScan(scanResult);
					event.commit();
				}
			}
		}

		private List<Point2f> calculatePoints(DataHeader header, byte[] data) {
			if (header.getPacketType() == PacketType.ZERO) {
				return Collections.emptyList();
			}
			List<Point2f> points = new ArrayList<>(data.length / 2);
			for (int i = 0; i < data.length / 2; i++) {
				// Distance in mm according to protocol
				float distance = DataHeader.getFromShort(data, i * 2) / 4.0f;
				float angle = header.getAngleAt(i, distance);
				if (angle >= 360.0) {
					angle -= 360.0;
				}

				// Transpose to -180, 180
				float relabeledAngle = angle > 180 ? angle - 360 : angle;

				Point2f point = Point2f.fromPolar(distance / 1000.0f, (float) Math.toRadians(relabeledAngle));

				// If we've found an angle over 130 AND a negative one, we've
				// likely crossed the boundary and it's time to send it all off.
				if (state == RetrieverState.NORMAL && relabeledAngle > 150) {
					state = RetrieverState.ENDING;
				}

				if (state == RetrieverState.ENDING || state == RetrieverState.ENDED) {
					if (relabeledAngle < 0) {
						survivors.add(point);
						state = RetrieverState.ENDED;
						continue;
					}
				}
				points.add(point);
			}
			return points;
		}

		public void stopScanning() {
			try {
				setScanning(false);
			} catch (IllegalStateException | IOException | InterruptedException | TimeoutException e) {
				// Do not care...
			}
		}
	}

	/**
	 * Default constructor. Will attempt to auto detect the USB to UART bridge
	 * controller included with the YDLidar device. Will default to sending
	 * scans for 360 degrees from -180 to 180.
	 * 
	 * @param receiver
	 *            call back for the receiver of the scans.
	 * 
	 * @throws InterruptedException
	 * @throws IOException
	 */
	public YDLidarDevice(ScanReceiver receiver) throws IOException, InterruptedException {
		this(SERIAL_PORT_AUTO, receiver);
	}

	/**
	 * Constructor.
	 * 
	 * @param serialPort
	 *            the serial port to use, or SERIAL_PORT_AUTO if an attempt to
	 *            auto resolve should be made.
	 * 
	 * @param receiver
	 *            call back for the receiver of the scans.
	 * 
	 * @throws InterruptedException
	 * @throws IOException
	 */
	public YDLidarDevice(String serialPort, ScanReceiver receiver) throws IOException, InterruptedException {
		this.receiver = receiver;
		if (SERIAL_PORT_AUTO.equals(serialPort)) {
			this.serialPort = autoResolveSerialPort();
		} else {
			this.serialPort = serialPort;
		}
		serial = SerialFactory.createInstance();
		serial.open(this.serialPort, BAUD_RATE);
	}

	/**
	 * Attempts to do a soft restart of the device.
	 * 
	 * @throws IOException
	 * @throws IllegalStateException
	 */
	public void restart() throws IllegalStateException, IOException {
		sendCommand(Command.RESTART);
	}

	/**
	 * Returns information about the ydlidar, such as the version.
	 * 
	 * @return information about the ydlidar, such as the version.
	 * 
	 * @throws IOException
	 *             on communication error.
	 * @throws InterruptedException
	 *             if the thread was interrupted.
	 * @throws TimeoutException
	 *             if the {@link RangingFrequency} could not be read in time.
	 */
	public DeviceInfo getDeviceInfo() throws IllegalStateException, IOException, InterruptedException, TimeoutException {
		synchronized (this) {
			disableDataCapturing();
			sendCommand(Command.GET_DEVICE_INFO);
			ResponseHeader response = readResponseHeader(DEFAULT_SERIAL_TIMEOUT);
			validateResponseType(response, ResponseType.DEVICE_INFO);
			byte[] readData = SerialUtil.readBytes(serial, response.getResponseLength(), 800);
			byte[] serialVersion = new byte[16];
			System.arraycopy(readData, 4, serialVersion, 0, serialVersion.length);
			return new DeviceInfo(readData[0], readData[1] << 8 + readData[2], readData[3], serialVersion);
		}
	}

	/**
	 * Returns health information about the ydlidar.
	 * 
	 * @return health information about the ydlidar.
	 * 
	 * @throws IOException
	 *             on communication error.
	 * @throws InterruptedException
	 *             if the thread was interrupted.
	 * @throws TimeoutException
	 *             if the {@link RangingFrequency} could not be read in time.
	 */
	public HealthInfo getHealthInfo() throws IllegalStateException, IOException, InterruptedException, TimeoutException {
		synchronized (this) {
			disableDataCapturing();
			sendCommand(Command.GET_DEVICE_HEALTH);
			ResponseHeader response = readResponseHeader(800);
			validateResponseType(response, ResponseType.DEVICE_HEALTH);
			byte[] readData = SerialUtil.readBytes(serial, response.getResponseLength(), 800);
			return new HealthInfo(HealthStatus.fromStatusCode(readData[0]), (short) (readData[1] << 8 + readData[2]));
		}
	}

	/**
	 * Returns the currently set {@link RangingFrequency}.
	 * 
	 * @return the currently set {@link RangingFrequency}, or null.
	 * 
	 * @throws IOException
	 *             on communication error.
	 * @throws InterruptedException
	 *             if the thread was interrupted.
	 * @throws TimeoutException
	 *             if the {@link RangingFrequency} could not be read in time.
	 */
	public RangingFrequency getRangingFrequency() throws IOException, InterruptedException, TimeoutException {
		synchronized (this) {
			disableDataCapturing();
			sendCommand(Command.GET_RANGING_FREQUENCY);
			ResponseHeader response = readResponseHeader(800);
			validateResponseType(response, ResponseType.DEVICE_INFO);
			byte[] readData = SerialUtil.readBytes(serial, response.getResponseLength(), 800);
			return RangingFrequency.fromFrequencyCode(readData[0]);
		}
	}

	/**
	 * Sets the ranging frequency.
	 * 
	 * @param rangingFrequency
	 *            the ranging frequency to set.
	 * 
	 * @throws IOException
	 *             on communication error.
	 * @throws InterruptedException
	 *             if the thread was interrupted.
	 * @throws TimeoutException
	 *             if the {@link RangingFrequency} could not be read in time.
	 */
	public void setRangingFrequency(RangingFrequency rangingFrequency)
			throws IOException, IllegalStateException, InterruptedException, TimeoutException {
		synchronized (this) {
			disableDataCapturing();
			sendCommand(Command.SET_RANGING_FREQUENCY, new byte[] { rangingFrequency.getFrequencyCode() });
			ResponseHeader response = readResponseHeader(800);
			validateResponseType(response, ResponseType.DEVICE_INFO);
			byte[] readData = SerialUtil.readBytes(serial, response.getResponseLength(), 800);
			if (readData.length != 1 || readData[0] != rangingFrequency.getFrequencyCode()) {
				throw new IOException("Unexpected response " + readData.length);
			}
		}
	}

	/**
	 * Starts the data capturing. Remember to keep reading data
	 * 
	 * @param enable
	 *            true to start capturing data.
	 */
	public void setScanning(boolean enable) throws IOException, InterruptedException, TimeoutException {
		synchronized (this) {
			if (enable) {
				if (isScanning) {
					return;
				}
				startMotor();
				sendCommand(Command.SCAN);
				ResponseHeader response = readResponseHeader(800);
				validateResponseType(response, ResponseType.MEASUREMENT);
				if (response.getResponseMode() != ResponseMode.CONTINUOUS) {
					throw new IOException("Expected a continuous response type");
				}
				isScanning = true;
				Thread t = new Thread(new DataRetriever(), "ydlidar data retriever");
				t.setDaemon(true);
				t.start();
			} else {
				isScanning = false;
				disableDataCapturing();
			}
		}
	}

	/**
	 * Sets the low power mode.
	 * 
	 * @throws IOException
	 *             on communication error.
	 * @throws InterruptedException
	 *             if the thread was interrupted.
	 * @throws TimeoutException
	 *             if the {@link RangingFrequency} could not be read in time.
	 */
	public void setIdleMode(IdleMode mode) throws IOException, InterruptedException, TimeoutException {
		synchronized (this) {
			disableDataCapturing();
			if (mode == IdleMode.LOW_POWER) {
				sendCommand(Command.LOW_POWER_CONSUMPTION);
			} else {
				sendCommand(Command.LOW_POWER_SHUTDOWN);
			}
			ResponseHeader response = readResponseHeader(800);
			validateResponseType(response, ResponseType.DEVICE_INFO);
			byte[] readData = SerialUtil.readBytes(serial, response.getResponseLength(), 800);
			int expectedResponse = mode == IdleMode.LOW_POWER ? 0x01 : 0x00;
			if (readData.length != 1 || readData[0] != expectedResponse) {
				throw new IOException("Unexpected response " + readData.length);
			}
		}
	}

	/**
	 * Shuts down the ydlidar and releases resources. After this, no more
	 * communication will be possible.
	 */
	public void shutdown() {
		synchronized (this) {
			try {
				disableDataCapturing();
				serial.close();
			} catch (IllegalStateException | IOException | InterruptedException e) {
				LOGGER.log(Level.WARNING, "Problem shutting down ydlidar serial", e);
			}
		}
	}

	@Override
	public String toString() {
		return "ydlidar@" + serialPort;
	}

	private void sendCommand(Command command) throws IOException {
		sendCommand(command, null);
	}

	/**
	 * Stops the capturing of data and shuts down the motor.
	 * 
	 * @throws IOException
	 *             on communication error.
	 * 
	 * @throws InterruptedException
	 *             if the thread was interrupted.
	 * 
	 */
	private void disableDataCapturing() throws IOException, InterruptedException {
		synchronized (this) {
			sendCommand(Command.STOP);
			stopMotor();
		}
	}

	private void stopMotor() throws IllegalStateException, IOException, InterruptedException {
		synchronized (this) {
			serial.setDTR(false);
			Thread.sleep(500);
		}
	}

	private void startMotor() throws IllegalStateException, IOException, InterruptedException {
		synchronized (this) {
			serial.setDTR(true);
			Thread.sleep(500);
		}
	}

	private ResponseHeader readResponseHeader(long timeout)
			throws IllegalStateException, IOException, InterruptedException, TimeoutException {
		byte[] readBytes = SerialUtil.readBytes(serial, ResponseHeader.RESPONSE_HEADER_LENGTH, timeout);
		return new ResponseHeader(readBytes);
	}

	private DataHeader readDataHeader(long timeout) throws IllegalStateException, IOException, InterruptedException, TimeoutException {
		ByteBuffer buffer = ByteBuffer.allocate(DataHeader.DATA_HEADER_LENGTH);
		while (true) {
			// We'll read one byte at a time until we're in data sync...
			SerialUtil.readBytes(buffer, serial, 1, timeout);
			if (buffer.get(0) == DataHeader.ANSWER_SYNC_BYTE1) {
				SerialUtil.readBytes(buffer, serial, 1, timeout);
				if (DataHeader.isDataHeaderStart(buffer.array())) {
					SerialUtil.readBytes(buffer, serial, DataHeader.DATA_HEADER_LENGTH - 2, timeout);
					return new DataHeader(buffer.array());
				}
			}
			buffer.clear();
		}
	}

	private byte[] readData(DataHeader header, int timeout)
			throws IllegalStateException, IOException, InterruptedException, TimeoutException {
		return SerialUtil.readBytes(serial, header.getDataLength(), timeout);
	}

	private void sendCommand(Command command, byte[] payload) throws IllegalStateException, IOException {
		int commandByte = command.getInstructionCode();
		if (payload != null) {
			commandByte |= CMDFLAG_HAS_PAYLOAD;
		}

		byte[] header = new byte[2];
		header[0] = CMD_SYNC_BYTE;
		header[1] = (byte) commandByte;
		serial.write(header);
		if (payload != null) {
			int checksum = 0;
			checksum ^= CMD_SYNC_BYTE;
			checksum ^= commandByte;
			checksum ^= (payload.length & 0xFF);
			serial.write((byte) payload.length);
			serial.write(payload);
			serial.write((byte) checksum);
		}
	}

	/*
	 * If this fails, throw an exception. The user should specify the serial
	 * port to use manually instead.
	 */
	private static String autoResolveSerialPort() throws IOException, InterruptedException {
		Set<SerialDeviceDescriptor> availableUSBSerialDevices = SerialUtil.getAvailableUSBSerialDevices();
		for (SerialDeviceDescriptor descriptor : availableUSBSerialDevices) {
			if (VENDOR_ID.equals(descriptor.getVendorId()) && PRODUCT_ID.equals(descriptor.getProductId())) {
				LOGGER.info("Bound ydlidar to " + descriptor);
				return descriptor.getPath();
			}
		}
		throw new IOException("Failed to auto resolve the serial port used by the ydlidar.");
	}

	private static void validateResponseType(ResponseHeader header, ResponseType expected) throws IOException {
		if (!header.isValid()) {
			throw new IOException("Got bad response!");
		}
		if (header.getResponseType() != expected) {
			throw new IOException("Got the wrong response type. Should have been " + expected + ". Got " + header.getResponseType() + ".");
		}

	}

	/**
	 * Just trying something out...
	 * 
	 * @throws IOException
	 *             on communication error.
	 * @throws InterruptedException
	 *             if the thread was interrupted.
	 * @throws TimeoutException
	 *             if the {@link RangingFrequency} could not be read in time.
	 */
	public static void main(String[] args) throws IOException, InterruptedException, TimeoutException {
		YDLidarDevice device = new YDLidarDevice(new ScanReceiver() {
			int counter;

			@Override
			public void onScan(ScanResult2D scanResult) {
				System.out.println("Got scan result: " + scanResult);
				if (counter++ % 20 == 0) {
					System.out.println("--- Points ---");

					List<Point2f> points = scanResult.getPoints();
					Collections.sort(points, new Comparator<Point2f>() {
						@Override
						public int compare(Point2f p1, Point2f p2) {
							return Float.compare(p1.getAngle(), p2.getAngle());
						}
					});
					System.out.println(points);
					System.out.println("--------------");
					System.out.println("");
				}
			}
		});
		System.out.println(device);
		System.out.println(device.getDeviceInfo());
		System.out.println(device.getHealthInfo());
		System.out.println("Ranging Frequency = " + device.getRangingFrequency());
		System.out.println("Will capture data for 10 seconds...");
		device.setScanning(true);
		Thread.sleep(10000);
		device.setScanning(false);
		device.shutdown();
		System.out.println("Done!");
		// Naughty that this has to be done... Perhaps fix Pi4J?
		SerialFactory.shutdown();
	}
}
