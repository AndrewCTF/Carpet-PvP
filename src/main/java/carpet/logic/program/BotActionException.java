package carpet.logic.program;

/**
 * An action could not be carried out, for a reason the program's author should be told.
 */
public class BotActionException extends RuntimeException
{
    public BotActionException(String message)
    {
        super(message);
    }
}
