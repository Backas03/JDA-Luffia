package kr.kro.backas.command.api;

import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

public interface SlashCommandSource extends CommandInfo {
    SlashCommandData buildCommand();
    void onTriggered(SlashCommandInteractionEvent event);

    default void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {

    }
}
