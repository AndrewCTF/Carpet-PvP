package carpet.script.utils;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.List;

public final class SnoopyCommandSource
{
    private SnoopyCommandSource() {}

    public static CommandSourceStack wrap(CommandSourceStack original, Component[] errorOut, List<Component> chatOut)
    {
        return original.withSource(new CapturingSource(errorOut, chatOut));
    }

    private static final class CapturingSource implements CommandSource
    {
        private static final TextColor ERROR_COLOR = TextColor.fromLegacyFormat(ChatFormatting.RED);

        private final Component[] errorOut;
        private final List<Component> chatOut;

        private CapturingSource(Component[] errorOut, List<Component> chatOut)
        {
            this.errorOut = errorOut;
            this.chatOut = chatOut;
        }

        @Override
        public void sendSystemMessage(Component message)
        {
            if (chatOut != null)
            {
                chatOut.add(message);
            }

            if (errorOut != null && errorOut.length > 0)
            {
                Style style = message.getStyle();
                TextColor color = style.getColor();
                if (color != null && color.equals(ERROR_COLOR))
                {
                    errorOut[0] = message;
                }
            }
        }

        @Override
        public boolean acceptsSuccess()
        {
            return true;
        }

        @Override
        public boolean acceptsFailure()
        {
            return true;
        }

        @Override
        public boolean shouldInformAdmins()
        {
            return false;
        }
    }
}
