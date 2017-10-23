import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

/**
 * UDP server for file transfer
 */
public class Server {

    private final int MAX_PACKET_SIZE = 1024;

    private final int SEND_WINDOW = 5;

    private final String PATH = "files/";

    /** Timeout in milliseconds */
    private final int TIMEOUT = 2000;

    private final int ATTEMPTS = 100;

    private String files = "";

    private int serverPort;

    private DatagramSocket serverSocket;

    private InetAddress clientAddr;

    private int clientPort;

    private String requestedFile;


    /**
     * This will continuously prompt the user for a port number until valid.
     */
    private void promptUser() {
        Scanner scan = new Scanner(System.in);
        System.out.print("Please specify a port number: ");
        String input = scan.nextLine();

        try {
            serverPort = Integer.parseInt(input);
        } catch (NumberFormatException e) {
            System.err.println("Invalid port number");
            promptUser();
        }
        scan.close();
    }

    /**
     * Sets up the server socket to make sure it isn't using an in-use port.
     *
     * @throws SocketException If an error occurs setting up the socket
     */
    private void initializeServer() throws SocketException {
        try {
            serverSocket = new DatagramSocket(serverPort);
            serverSocket.setSoTimeout(TIMEOUT);
        } catch (SocketException e) {
            String message = "Error occurred hosting server on port ";
            message += serverPort;
            message += "\nIs there another instance of this server?";
            throw new SocketException(message);
        }

        System.out.println("Server started on port " + serverPort);
    }

    /**
     * Check for available files in a given path.
     */
    private void getAvailableFiles(final String path) {
        File folder = new File(path);
        File[] fileArray = folder.listFiles();

        files = "";

        if (fileArray == null) return;

        for (File file : fileArray) {

            if (file.isFile()) {
                files += file.getName() + ";";
            }
        }
    }

    /**
     * Method used to receive packets off the server socket.
     *
     * @return The received DatagramPacket
     * @throws SocketTimeoutException For all socket exceptions
     * @throws ChecksumException      If the checksum had an error in it
     */
    private DatagramPacket receive() throws SocketTimeoutException, ChecksumException {
        byte[] buf = new byte[MAX_PACKET_SIZE];

        DatagramPacket packet = new DatagramPacket(buf, buf.length);

        try {
            serverSocket.receive(packet);
        } catch (SocketTimeoutException se) {
            return null;
        } catch (IOException e) {
            System.err.println(e.getMessage());
            return null;
        }

        int expected = Header.calculateChecksum(buf);
        int received = new Header(buf).getChecksum();

        if (expected != received) {
            throw new ChecksumException(expected, received);
        }

        return packet;
    }

    /**
     * Establishes connection with client and gets the received file name.
     */
    private void establishConnection() {
        DatagramPacket receivedPacket = null;

        do {
            try {
                receivedPacket = receive();
            } catch (SocketTimeoutException e) {
                e.printStackTrace();
            } catch (ChecksumException bc) {
                System.err.println(bc.getMessage());
            }
        } while (receivedPacket == null);

        byte[] data = receivedPacket.getData();
        Header head = new Header(data);

        // If received non SYN packet, retry
        if (!head.getSynFlag() || head.getReqFlag() || head.getAckFlag()) {

            System.err.println("Received unexpected packet");
            establishConnection();
        }

        clientAddr = receivedPacket.getAddress();
        clientPort = receivedPacket.getPort();

        System.out.println("Received SYN packet from " + clientAddr.getHostAddress() + " " + " on port " + clientPort);

        getAvailableFiles(PATH);

        Header ackHead = new Header();
        ackHead.setAckFlag(true);
        ackHead.setSynFlag(true);
        ackHead.setChecksum(files.getBytes());

        byte[] headData = ackHead.getBytes();

        int ackPackLen = headData.length + files.length();

        byte[] packData = new byte[ackPackLen];

        // Populate ACK data array with header data
        System.arraycopy(headData, 0, packData, 0, headData.length);

        // Populate ACK data array with list of files
        for (int i = 0; i < files.length(); i++) {
            packData[i + headData.length] = (byte) files.charAt(i);
        }

        // Send ACK header to client
        try {
            send(packData);
            System.out.println("Sent ACK to client");
        } catch (IOException e) {
            establishConnection();
        }

        DatagramPacket reqPack = null;

        // Wait for REQ from client
        do {
            try {
                reqPack = receive();

                Header receivedHeader = new Header(receivedPacket.getData());

                // Check for proper header
                if (!receivedHeader.getReqFlag() || receivedHeader.getAckFlag() || receivedHeader.getSynFlag()) {
                    System.err.println("Received unexpected packet");
                    continue;
                }

            } catch (SocketTimeoutException e) {
                continue;
            } catch (ChecksumException bc) {
                System.err.println(bc.getMessage());
                continue;
            }
        } while (reqPack == null);

        byte[] bytes = reqPack.getData();
        requestedFile = "";

        for (int i = Header.HEADER_SIZE; i < bytes.length; i++) {
            char c = (char) bytes[i];

            if (c == '\0') break;

            requestedFile += c;
        }

        System.out.println("Client is requesting \"" + requestedFile + "\"");
    }

    /**
     * Reads chunks of a file at a given offset and size
     *
     * @param position The offset
     * @param size     The length of bytes to read
     * @return The read byte array
     * @throws IOException
     */
    private byte[] readFromFile(int position, int size) throws IOException {
        RandomAccessFile file = new RandomAccessFile(PATH + requestedFile, "r");
        file.seek(position);
        byte[] bytes = new byte[size];
        file.read(bytes);
        file.close();
        return bytes;
    }

    /**
     * Sends the file to the client
     *
     * @param fileLength Length of the file in bytes
     * @param numPackets Total number of packets it should send
     * @return True if everything went okay
     * @throws IOException
     */
    private boolean sendFile(long fileLength, int numPackets) throws IOException {
        int lastAck = 0;

        int numRetry = 0;


        byte[] statusPacket = Header.createStatusPacket(true, numPackets,
                (int) fileLength);

        int lengthToRead = (MAX_PACKET_SIZE - Header.HEADER_SIZE) * SEND_WINDOW;
        final int MAX_DATA = MAX_PACKET_SIZE - Header.HEADER_SIZE;

        byte[] fileData = readFromFile(0, lengthToRead);
        if (fileData == null) {
            return false;
        }
        for (int i = 0; i < numPackets; i = lastAck) {

            if (i == 0) {
                send(statusPacket);
                System.out.println("Sending request acknowledgement to client");
            }

            int tempPosition = 0;
            // Sliding window here
            for (int x = i; x < SEND_WINDOW + i && x < numPackets; x++) {
                Header head = new Header();
                head.setSequenceNum(x + 1);


                int packetSize = (int) fileLength - (MAX_DATA) * x;

                if (packetSize > MAX_DATA) {
                    packetSize = MAX_DATA;
                }

                packetSize += Header.HEADER_SIZE;

                byte[] packetData = new byte[packetSize];


                // Populate packet byte array
                for (int k = Header.HEADER_SIZE; k < packetSize; k++) {
                    packetData[k] = fileData[(k - Header.HEADER_SIZE) + (tempPosition * MAX_DATA)];
                }
                tempPosition++;

                head.setChecksum(packetData);

                System.arraycopy(head.getBytes(), 0, packetData, 0, Header.HEADER_SIZE);

                try {
                    send(packetData);
                    System.out.println("\tSent packet number " + (x + 1));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            DatagramPacket ackPacket;

            try {
                ackPacket = receive();
                if (ackPacket == null) {
                    continue;
                }
                Header head = new Header(ackPacket.getData());

                if (!head.getAckFlag() || head.getReqFlag() || head.getSynFlag()) {
                    System.err.println("Received unexpected packet");
                    continue;
                }

            } catch (SocketTimeoutException e) {

                if (++numRetry > ATTEMPTS) {
                    System.err.println("Client not responding.");
                    return false;
                }

                System.err.println("Acknowledgement timed out. Resending " + "packet " + lastAck + "\n");
                continue;
            } catch (ChecksumException be) {
                System.err.println(be.getMessage());
                continue;
            }
            numRetry = 0;

            Header head = new Header(ackPacket.getData());
            lastAck = head.getSequenceNum();

            // Move file byte array over by full sliding window amount
            fileData = readFromFile(lastAck * MAX_DATA, lengthToRead);
            System.out.println(" Got acknowledgement of packet " + lastAck + "\n");
        }

        return true;
    }


    /**
     * Sends the given data to the most recent connected client on the port the server is hosted on.
     *
     * @param data Data to send
     * @throws IOException
     */
    private void send(byte[] data) throws IOException {

        DatagramPacket sendPacket = new DatagramPacket(data, data.length, clientAddr, clientPort);
        serverSocket.send(sendPacket);
    }

    /**
     * Starts the server
     *
     * @throws IOException
     */
    public void startServer() throws IOException {
        promptUser();
        initializeServer();

        while (true) {
            establishConnection();
            File file;
            try {
                file = new File(PATH + requestedFile);
            } catch (Exception e) {
                byte[] status = Header.createStatusPacket(false, 0, 0);
                send(status);

                System.err.println("File '" + requestedFile + "' not found.");
                continue;
            }


            int numPackets = (int) Math.ceil(((double) file.length()) /
                    ((double) MAX_PACKET_SIZE - Header.HEADER_SIZE));

            System.out.println("Starting file transfer");

            if (sendFile(file.length(), numPackets)) {
                System.out.println("File transfer complete.");
            }
        }
    }

    public static void main(String[] args) {

        Server s;

        try {
            s = new Server();
            s.startServer();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

