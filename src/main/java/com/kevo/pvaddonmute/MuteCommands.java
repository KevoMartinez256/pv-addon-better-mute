package com.kevo.pvaddonmute;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;

/**
 * Legacy wrapper: use com.kevo.pvaddonmute.commands.MuteCommands directly.
 */
@Deprecated
public final class MuteCommands {
    private MuteCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        com.kevo.pvaddonmute.commands.MuteCommands.register(dispatcher);
    }
}
