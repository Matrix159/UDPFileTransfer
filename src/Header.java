/**
 * Represents a datagram packet header
 */
public class Header {

    /**
     * Header length in bytes
     */
    public static final int HEADER_SIZE = 8;

    /**
     * Header data
     */
    private byte[] data;

    public Header() {
        data = new byte[HEADER_SIZE];
    }

    public Header(byte[] data) {
        this.data = data;
    }

    /**
     * Sets the sequence number into the beginning of the header.
     * Takes up 4 bytes of the header.
     *
     * @param seqNum The sequence number
     */
    public void setSequenceNum(int seqNum) {
        byte[] converted = intToByteArray(4, seqNum);

        for (int i = 0; i < converted.length; i++) {
            data[i] = converted[i];
        }
    }

    /**
     * Sets the sync flag, true if file is requesting list of files.
     *
     * @param flag
     */
    public void setSynFlag(boolean flag) {
        if (flag) {
            data[6] |= 1 << 7;
        } else {
            data[6] &= ~(1 << 7);
        }
    }

    /**
     * Sets the acknowledgement flag, true for acknowledgement packets.
     *
     * @param flag
     */
    public void setAckFlag(boolean flag) {
        if (flag) {
            data[6] |= 1 << 6;
        } else {
            data[6] &= ~(1 << 6);
        }
    }

    /**
     * Sets the request flag, true if this packet is the file name request packet.
     *
     * @param flag
     */
    public void setReqFlag(boolean flag) {
        if (flag) {
            data[6] |= 1 << 5;
        } else {
            data[6] &= ~(1 << 5);
        }
    }

    /**
     * Gets the sync flag.
     *
     * @return
     */
    public boolean getSynFlag() {
        int x = (1 << 7);
        return (data[6] & x) == x;
    }

    /**
     * Gets the acknowledgement flag.
     *
     * @return
     */
    public boolean getAckFlag() {
        int x = (1 << 6);
        return (data[6] & x) == x;
    }

    /**
     * Gets the request flag.
     *
     * @return
     */
    public boolean getReqFlag() {
        int x = (1 << 5);
        return (data[6] & x) == x;
    }

    /**
     * Gets the sequence number.
     *
     * @return
     */
    public int getSequenceNum() {
        return (data[0] << 24 | data[1] << 16 | data[2] << 8
                | data[3] & 0xFF);
    }

    /**
     * Gets the 16-bit checksum as an int
     *
     * @return
     */
    public int getChecksum() {
        return (data[4] << 8 | data[5] & 0xFF) & 0xFFFF;
    }

    /**
     * Returns the header data
     *
     * @return
     */
    public byte[] getBytes() {
        return data;
    }

    /**
     * Calculates and sets the checksum of the header data plus the passed in data.
     *
     * @param dataField The data to turn into a checksum
     */
    public void setChecksum(byte[] dataField) {
        int hLen = data.length;
        int dLen = dataField.length;

        byte[] checksumData = new byte[hLen + dLen];
        System.arraycopy(data, 0, checksumData, 0, hLen);
        System.arraycopy(dataField, 0, checksumData, hLen, dLen);

        int checksum = calculateChecksum(checksumData);

        data[4] = (byte) (checksum >>> 8);
        data[5] = (byte) (checksum);
    }

    /**
     * Sets the checksum of just the header data into the header.
     */
    public void setChecksum() {
        int checksum = calculateChecksum(data);

        data[4] = (byte) (checksum >>> 8);
        data[5] = (byte) (checksum);
    }

    /**
     * Utility method to convert an int into a byte array.
     *
     * @param numBytes  Number of bytes to spread this integer into
     * @param toConvert The int to convert
     * @return The converted int into a byte array
     */
    private byte[] intToByteArray(int numBytes, int toConvert) {
        byte[] data = new byte[numBytes];

        for (int i = numBytes - 1, offset = 0; i >= 0; i--, offset += 8) {
            data[i] = (byte) (toConvert >> offset);
        }

        return data;
    }

    /**
     * Creates a status packet to pass meta data to the client and server.
     *
     * @param good       If status is good
     * @param numPackets Number of packets that will be sent for file transfer
     * @param numBytes   Total number of bytes of the file
     * @return Status packet in byte array form
     */
    public static byte[] createStatusPacket(boolean good, int numPackets, int numBytes) {

        Header head = new Header();
        head.setAckFlag(true);
        head.setReqFlag(true);

        final int dataLen = 9;
        byte[] dataField = new byte[dataLen];

        dataField[0] = (byte) ((good ? 1 : 0) << 7);

        if (good) {
            byte[] convertedPack = head.intToByteArray(4, numPackets);
            byte[] convertedByte = head.intToByteArray(4, numBytes);

            System.arraycopy(convertedPack, 0, dataField, 1, 4);
            System.arraycopy(convertedByte, 0, dataField, 5, 4);
        }

        head.setChecksum(dataField);

        byte[] returnedStatusPacket = new byte[HEADER_SIZE + dataLen];

        for (int i = 0; i < HEADER_SIZE; i++) {
            returnedStatusPacket[i] = head.getBytes()[i];
        }

        for (int i = 0; i < dataLen; i++) {
            returnedStatusPacket[i + HEADER_SIZE] = dataField[i];
        }

        return returnedStatusPacket;
    }

    /**
     * Utility to calculate a checksum
     *
     * @param data Data to perform a checksum on
     * @return The checksum result
     */
    public static int calculateChecksum(final byte[] data) {
        byte[] buf = new byte[data.length];
        System.arraycopy(data, 0, buf, 0, buf.length);

        buf[4] = 0;
        buf[5] = 0;

        long sum = 0;

        for (int i = 0; i < buf.length; i++) {
            sum += (buf[i++] & 0xFF) << 8;

            // Check for odd length
            if (i == buf.length) break;

            sum += (buf[i] & 0xFF);
        }

        long carryFix = ((sum & 0xFFFF) + (sum >>> 16));
        return (int) ~carryFix & 0xFFFF;
    }
}