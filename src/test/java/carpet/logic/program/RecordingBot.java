package carpet.logic.program;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link Bot} that does nothing but write down what it was told to do and what it was asked, as
 * "method[arguments]". Questions are answered with false or 0 unless an answer was set for the method.
 */
final class RecordingBot implements InvocationHandler
{
    final List<String> calls = new ArrayList<>();
    final List<String> questions = new ArrayList<>();
    final Map<String, Object> answers = new HashMap<>();
    final Map<String, RuntimeException> failures = new HashMap<>();
    final Bot bot = (Bot) Proxy.newProxyInstance(Bot.class.getClassLoader(), new Class<?>[] {Bot.class}, this);

    @Override
    public Object invoke(Object proxy, Method method, Object[] args)
    {
        if (failures.containsKey(method.getName()))
        {
            throw failures.get(method.getName());
        }
        Class<?> returns = method.getReturnType();
        if (returns == void.class)
        {
            calls.add(method.getName() + Arrays.toString(args == null ? new Object[0] : args));
            return null;
        }
        questions.add(method.getName() + Arrays.toString(args == null ? new Object[0] : args));
        if (answers.containsKey(method.getName()))
        {
            return answers.get(method.getName());
        }
        return returns == boolean.class ? (Object) false : (Object) 0.0;
    }

    /**
     * @return how many commands (questions excluded) the bot was given whose name is the given one
     */
    long count(String method)
    {
        return calls.stream().filter(call -> call.startsWith(method + "[")).count();
    }
}
