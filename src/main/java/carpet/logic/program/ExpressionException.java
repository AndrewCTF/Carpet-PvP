package carpet.logic.program;

/** An expression that cannot be parsed or evaluated; the message already says where, when it can. */
public final class ExpressionException extends IllegalArgumentException
{
    private final int position;

    public ExpressionException(String message, int position)
    {
        super(message);
        this.position = position;
    }

    /** The 0-based index into the source the problem was found at, or -1 when it has no place. */
    public int position()
    {
        return position;
    }
}
