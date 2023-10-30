package kr.kro.backas.command.api;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

public interface SlashCommandSource {
    SlashCommandData buildCommand();
    void onTriggered(SlashCommandInteractionEvent event);
}
