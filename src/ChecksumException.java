
/**
 * Custom checksum exception that prints out what expected and received bytes of the checksum are.
 */
public class ChecksumException extends Exception {

	private static final long serialVersionUID = 1L;

	/** Expected and received ints to represent checksum bytes */
	private int expected, received;

    /**
     * Takes the expected bytes and received bytes as ints
     * @param expected Expected bytes as int
     * @param received Received bytes as int
     */
	public ChecksumException(int expected, int received) {
		this.expected = expected;
		this.received = received;
	}

	@Override
	public String getMessage() {
		return "Caught bad checksum. Expected: " + expected + " Got: " + received;
	}
}
