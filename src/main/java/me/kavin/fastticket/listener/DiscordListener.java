package me.kavin.fastticket.listener;

import java.time.OffsetDateTime;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import me.kavin.fastticket.Main;
import me.kavin.fastticket.botlist.BotListPoster;
import me.kavin.fastticket.botlist.tasks.DiscordBotsPostTask;
import me.kavin.fastticket.botlist.tasks.DivineDiscordBotsPostTask;
import me.kavin.fastticket.command.Command;
import me.kavin.fastticket.command.CommandExecutor;
import me.kavin.fastticket.command.CommandManager;
import me.kavin.fastticket.consts.Constants;
import me.kavin.fastticket.utils.Multithreading;
import me.kavin.fastticket.utils.SettingsHelper;
import me.kavin.fastticket.utils.TicketUtils;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Activity.ActivityType;
import net.dv8tion.jda.api.entities.ChannelType;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.events.ReadyEvent;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.guild.react.GuildMessageReactionAddEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class DiscordListener extends ListenerAdapter {

	private void setPresence() {
		for (JDA jda : Main.api.getShards())
			jda.getPresence().setActivity(
					Activity.of(ActivityType.WATCHING, "-help | " + Main.api.getGuilds().size() + " Servers!"));
	}

	@Override
	public void onReady(ReadyEvent event) {
		event.getJDA().getPresence().setStatus(OnlineStatus.ONLINE);
		event.getJDA().getPresence().setActivity(
				Activity.of(ActivityType.WATCHING, "-help | " + Main.api.getGuilds().size() + " Servers!"));
		new CommandManager();
		{
			ObjectArrayList<Runnable> tasks = new ObjectArrayList<>();
			tasks.add(new DivineDiscordBotsPostTask());
			tasks.add(new DiscordBotsPostTask());
			new BotListPoster(tasks).initialize();
		}

		long time = System.currentTimeMillis();
		long delay = TimeUnit.HOURS.toMillis(1) - (time % TimeUnit.HOURS.toMillis(1));

		new Timer().scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {

				// close tickets without a new message in the last 48H

				for (TextChannel tc : event.getJDA().getTextChannels())
					if (tc.getName().startsWith("ticket-") && tc.getTopic() != null
							&& tc.getTopic().startsWith("ticket|"))
						if (SettingsHelper.getInstance().getAutoClose(tc.getGuild().getIdLong())) {
							try {
								long msgId = tc.getLatestMessageIdLong();
								if (tc.getHistoryAround(msgId, 1).submit().get().getRetrievedHistory().get(0)
										.getTimeCreated().plusHours(48).isBefore(OffsetDateTime.now())) {
									TicketUtils.postLogs("Inactive Ticket", tc.getGuild().getIdLong(),
											event.getJDA().getSelfUser(),
											event.getJDA().getUserById(tc.getTopic().split("\\|")[1]), tc);
									tc.delete().queue();
								}
							} catch (IllegalStateException e) {
								// Invalid Ticket or unable to read messages?
							} catch (InterruptedException | ExecutionException e) {
								// ignored
							}
						}
			}
		}, delay, TimeUnit.HOURS.toMillis(1));
	}

	@Override
	public void onGuildJoin(GuildJoinEvent event) {
		setPresence();
	}

	@Override
	public void onGuildLeave(GuildLeaveEvent event) {
		setPresence();
	}

	@Override
	public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
		for (TextChannel tc : event.getGuild().getTextChannels()) {
			if (tc.getTopic() != null && tc.getTopic().startsWith("ticket|")
					&& tc.getTopic().split("\\|")[1].equals(event.getUser().getId())) {
				TicketUtils.postLogs("User left", event.getGuild().getIdLong(), event.getJDA().getSelfUser(),
						event.getUser(), tc);
				tc.delete().queue();
			}
		}
	}

	@Override
	public void onMessageReceived(MessageReceivedEvent event) {
		if (event.isFromType(ChannelType.PRIVATE) || event.getAuthor().isBot())
			return;

		{
			if (event.getMessage().getContentRaw().length() > 0 && event.getMessage().getContentRaw().charAt(0) == '-')
				for (Command cmd : CommandManager.commands)
					if (event.getMessage().getContentRaw().split(" ")[0].equalsIgnoreCase(cmd.getPrefix()))
						Multithreading.runAsync(new CommandExecutor(cmd, event));
		}

	}

	@Override
	public void onGuildMessageReactionAdd(GuildMessageReactionAddEvent event) {
		Multithreading.runAsync(new Runnable() {
			@Override
			public void run() {
				if (event.getUser().isBot())
					return;

				if (event.getMessageIdLong() == SettingsHelper.getInstance()
						.getReactionMessageId(event.getGuild().getIdLong()) && !event.getReactionEmote().isEmoji()
						&& event.getReactionEmote().getIdLong() == Constants.OPEN_EMOJI_ID) {
					event.getReaction().removeReaction(event.getUser()).queue();

					for (TextChannel tc : event.getGuild().getTextChannels())
						if (tc.getName().startsWith("ticket-") && tc.getTopic() != null
								&& tc.getTopic().startsWith("ticket|"))
							if (event.getUser().getId().equals(tc.getTopic().split("\\|")[1]))
								return;

					try {
						TextChannel tc = TicketUtils.createTicket(event.getGuild(), event.getMember(),
								"Reacted with emoji.");
						tc.sendMessage(event.getUser().getAsMention()).queue();
					} catch (Exception e) {
						// ignored
					}
				}

				if (!event.getReactionEmote().isEmoji()
						&& event.getReactionEmote().getIdLong() == Constants.CLOSE_EMOJI_ID) {
					TextChannel tc = event.getChannel();
					if (tc.getName().startsWith("ticket-") && tc.getTopic() != null
							&& tc.getTopic().startsWith("ticket|"))
						try {
							Message msg = event.getChannel().getHistoryAround(event.getMessageIdLong(), 1).submit()
									.get().getRetrievedHistory().get(0);
							if (msg.getAuthor().getIdLong() == event.getJDA().getSelfUser().getIdLong()) {
								TicketUtils.postLogs("Reacted with emoji", event.getGuild().getIdLong(),
										event.getUser(), event.getJDA().getUserById(tc.getTopic().split("\\|")[1]), tc);
								tc.delete().queue();
							}
						} catch (Exception e) {
							// ignored
						}
				}

			}
		});
	}
}