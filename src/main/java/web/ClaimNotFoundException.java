package web;

public class ClaimNotFoundException extends RuntimeException {
    public ClaimNotFoundException(long id) {
        super("Claim " + id + " was not found");
    }
}
