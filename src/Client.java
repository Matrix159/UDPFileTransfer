import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Scanner;

/**
 * UDP file transfer client.
 */
public class Client {
	
	private final int WINDOW_SIZE = 5;
	
	private String fileName;
	
	private int numPackets;
	
	private int fileSize;
	
	/** Timeout in milliseconds */
	private final int TIMEOUT = 5000;
	
	private int clientPort;
	
	private int serverPort;
	
	private InetAddress serverAddress;
	
	private DatagramSocket clientSocket;

	public void startClient() throws IOException{
        // Prompt the user for inputs until valid inputs are given
        promptUser();

        // Initializing the client socket
        try {
            clientSocket = new DatagramSocket(clientPort);
            clientSocket.setSoTimeout(TIMEOUT);
        } catch (SocketException e) {
            String message = "Problem starting client on port ";
            message += clientPort;
            message += "\nIs there another instance of this client?";
            throw new SocketException(message);
        }

        // If something bad happened during connection establishment
        if (!establishConnection()) return;

        if (!requestFile()) return;

        acceptFile();
    }

    /**
     * Prompt user until all inputs are valid
     */
    private void promptUser() {
		Scanner scan = new Scanner(System.in);
		
		System.out.print("Please specify a client port number: ");
		String input = scan.nextLine();
		
		try {
			clientPort = Integer.parseInt(input);
		} catch (NumberFormatException e) {
			System.err.println("Invalid port number");
			promptUser();
		}
		System.out.print("Enter server port number: ");
		input = scan.nextLine();

		try {
			serverPort = Integer.parseInt(input);
		} catch (NumberFormatException e) {
			System.err.println("Invalid port number");
			promptUser();
		}

		System.out.print("Enter server IP address: ");
		String destAddress = scan.nextLine();
		
		try {
			serverAddress = InetAddress.getByName(destAddress);
		} catch (UnknownHostException e) {
			System.err.println("Invalid IP address");
			promptUser();
		}
	}

    /**
     * Setup connection with server and get valid list of files
     * @return True if everything went okay
     * @throws IOException
     */
	private boolean establishConnection() throws IOException {
		String message = "Attempting to connect to server at: ";
		message += serverAddress.getHostAddress();
		message += " Port: " + serverPort;
		System.out.println(message);
		
		Header synHeader = new Header();
		synHeader.setSynFlag(true);
		synHeader.setChecksum();
		
		byte[] sendHeader = synHeader.getBytes();
		
		final int attempts = 3;
		
		DatagramPacket packet = null;
		
		// Waiting for SYN ACK
		for (int i = 1; i <= attempts; i++) {
			
			send(sendHeader);
			
			try {
				packet = receive();
				byte[] data = packet.getData();
				
				Header head = new Header(data);
				
				if (!head.getAckFlag() || !head.getSynFlag()) {
					System.err.println("Received unexpected packet");
					packet = null;
					i--;
					continue;
				}
				break;
				
			} catch (SocketTimeoutException e) {
				System.err.println("Connection attempt " + (i) + " timed out.");
			} catch (ChecksumException bc) {
				System.err.println(bc.getMessage());
			}
		}
		
		if (packet == null) {
			message = "Unable to establish connection";
			System.err.println(message);
			return false;
		}
		
		System.out.println("Got connection acknowledgement from server");
		
		byte[] bytes = packet.getData();
		String availableFiles = "";
		
		/* Loop through data field */
		for (int i = Header.HEADER_SIZE; i < bytes.length; i++) {
			availableFiles += (char) bytes[i];
		}
		
		if (availableFiles.isEmpty()) {
			System.err.println("Server has no files to send");
			return false;
		}
		
		String[] files = availableFiles.split(";");
		
		System.out.println("--------------------");
		System.out.println("Available files on " + 
				serverAddress.getHostAddress() + ":");
		
		for (String file : files) {
			System.out.println("\t" + file);
		}
		
		return true;
	}

    /**
     *
     * @return
     * @throws IOException
     */
	private boolean requestFile() throws IOException {
		Scanner scan = new Scanner(System.in);
		
		System.out.print("\nSelect a file: ");
		fileName = scan.next();
		scan.close();
		
		Header head = new Header();
		head.setReqFlag(true);
		head.setChecksum(fileName.getBytes());
		
		byte[] headData = head.getBytes();
		
		int reqPackLen = headData.length + fileName.length();
		
		byte[] packData = new byte[reqPackLen];

		// Populate REQ data array with header data
        System.arraycopy(headData, 0, packData, 0, headData.length);

		// Populate ACK data array with requested file
		for (int i = 0; i < fileName.length(); i++) {
			packData[i + headData.length] = (byte) fileName.charAt(i);
		}
		
		System.out.println("Requesting file \"" + fileName + "\"");
		
		final int attempts = 3;
		
		DatagramPacket reqAckPack = null;
		
		// Waiting for REQ ACK
		for (int i = 1; i <= attempts; i++) {
			
			send(packData);
			
			try {
				reqAckPack = receive();
				byte[] data = reqAckPack.getData();
				
				Header recvHead = new Header(data);
				
				if (!recvHead.getAckFlag() || !recvHead.getReqFlag() || 
						recvHead.getSynFlag()) {
					System.err.println("Received unexpected packet");
					reqAckPack = null;
					i--;
					continue;
				}
					
				break;
				
			} catch (SocketTimeoutException e) {
				System.err.println("Request " + (i) + " timed out.");
			} catch (ChecksumException bc) {
				System.err.println(bc.getMessage());
				i--;
			}
		}
		
		if (reqAckPack == null) {
			String msg = "Server not responding to request";
			System.err.println(msg);
			return false;
		}
		
		byte[] reqAckData = reqAckPack.getData();
		
		int statusCode = (int) reqAckData[Header.HEADER_SIZE] & 0xFF;
		
		// Checks for non "good" status
		if (statusCode != (1 << 7)) {
			String msg = "Server does not recognize requested file";
			System.err.println(msg);
			return false;
		}
		
		numPackets = ((reqAckData[Header.HEADER_SIZE + 1] & 0xFF) << 24 |
				(reqAckData[Header.HEADER_SIZE + 2] & 0xFF) << 16 |
				(reqAckData[Header.HEADER_SIZE + 3] & 0xFF) << 8 |
				(reqAckData[Header.HEADER_SIZE + 4] & 0xFF));
		
		fileSize = ((reqAckData[Header.HEADER_SIZE + 5] & 0xFF) << 24 |
				(reqAckData[Header.HEADER_SIZE + 6] & 0xFF) << 16 |
				(reqAckData[Header.HEADER_SIZE + 7] & 0xFF) << 8 |
				(reqAckData[Header.HEADER_SIZE + 8] & 0xFF));
		
		System.out.println("Got acknowledgement from server");
		String msg = "File \"" + fileName + "\" is " + fileSize + " bytes";
		System.out.println(msg + "\n");
		
		return true;
		
	}

    /**
     * Start creating file based on data received from server.
     * @throws IOException
     */
	private void acceptFile() throws IOException {
		int lastReceivedSeq = 0;
		int bytesReceived = 0;
		
		int attempted = 0;
		final int attempts = 3;
		
		final String path = "./" + fileName;
		
		File file = new File(path);
		
		if (file.exists()) {
			file.delete();
		}
		
		file.createNewFile();
		
		FileOutputStream fos = new FileOutputStream(path, true);
		
		while (lastReceivedSeq != numPackets) {
			int currentReceivedSeq = lastReceivedSeq;
			
			for (int i = 0; i < WINDOW_SIZE; i++) {
				DatagramPacket receivedPacket;

				try {
					receivedPacket = receive();
				} catch (SocketTimeoutException e) {
					break;
				} catch (ChecksumException bc) {
					System.err.println(bc.getMessage());
					i--;
					continue;
				}
				
				Header head = new Header(receivedPacket.getData());
				int seqNum = head.getSequenceNum();

				if (seqNum != lastReceivedSeq + 1) {
					String msg = "Got unexpected packet. Sequence number: ";
					msg += seqNum;
					System.err.println(msg);
					continue;
				}
				
				lastReceivedSeq = seqNum;
				
				byte[] bytes = receivedPacket.getData();
				int hSize = Header.HEADER_SIZE;
				
				if (lastReceivedSeq == numPackets) {
					byte[] temp = new byte[fileSize - bytesReceived + hSize];

					System.arraycopy(bytes, 0, temp, 0, temp.length);
					
					bytes = temp;
				}

				fos.write(bytes, hSize, bytes.length - hSize);

				bytesReceived += bytes.length - hSize;

				double percentage = (((double) bytesReceived) /
						((double) fileSize)) * 100;
				
				String msg = String.format("%s %d \t%4.2f%%", "Received packet number", lastReceivedSeq, percentage);
				System.out.println(msg);
				
				if (lastReceivedSeq == numPackets) break;
			}
			
			if (currentReceivedSeq == lastReceivedSeq) {
				attempted ++;
			} else {
				attempted = 0;
			}
			
			if (attempted >= attempts) {
				System.err.println("Server not responding.");
				return;
			}
			
			Header ackPack = new Header();
			ackPack.setAckFlag(true);
			ackPack.setSequenceNum(lastReceivedSeq);
			ackPack.setChecksum();
			
			send(ackPack.getBytes());
			System.out.println("Sending acknowledgement of packet "
					+ lastReceivedSeq + "\n");
		}
		
		System.out.println("File transfer complete.");
		
		fos.close();
	}

    /**
     * Sends given data to server.
     * @param data Byte array of data to send
     * @throws IOException
     */
	private void send(byte[] data) throws IOException {
		DatagramPacket sendPacket = new DatagramPacket(data, data.length,
				serverAddress, serverPort);
		
		clientSocket.send(sendPacket);
	}

    /**
     * Receives data from the server
     * @return The packet received from server
     * @throws IOException
     * @throws ChecksumException If checksum had an error in it
     */
	private DatagramPacket receive() throws IOException, ChecksumException
	{
				
		byte[] receivedData = new byte[1024];
		
		DatagramPacket receivedPacket = new DatagramPacket(receivedData,receivedData.length);
		
		clientSocket.receive(receivedPacket);

		int expected = Header.calculateChecksum(receivedData);
		int received = new Header(receivedData).getChecksum();
		
		if (expected != received)
			throw new ChecksumException(expected, received);
		
		return receivedPacket;
	}
	
	public static void main(String[] args) {

	    Client client = new Client();
	    try {
	        client.startClient();
        } catch(IOException ex) {
	        ex.printStackTrace();
        }
	}
}
