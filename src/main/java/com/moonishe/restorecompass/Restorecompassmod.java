package com.moonishe.restorecompass;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

public class Restorecompassmod implements ModInitializer {
	public static final String MOD_ID = "restorecompassmod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final String RESTORE_COMPASS_TAG = "RestoreCompass";
	private static final double MIN_REMOVAL_DISTANCE = 3.0;
	private static final long COMPASS_COOLDOWN = 30000; // 30 секунд между выдачами компасов
	private static final int MAX_DEATHS_PER_MINUTE = 3; // Максимум 3 смерти в минуту

	// Используем ConcurrentHashMap для потокобезопасности
	private static final ConcurrentHashMap<UUID, Long> compassGivenPlayers = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<UUID, Boolean> hadItemsOnDeath = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<UUID, DeathTracker> deathTrackers = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<UUID, ResourceKey<Level>> playerDimensions = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<UUID, ScheduledFuture<?>> scheduledCleanups = new ConcurrentHashMap<>();

	// Создаем пул потоков для отложенных задач
	private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

	private int tickCounter = 0;

	// Класс для отслеживания смертей
	private static class DeathTracker {
		private final long[] deathTimes = new long[MAX_DEATHS_PER_MINUTE];
		private int index = 0;

		public boolean canGiveCompass() {
			long currentTime = System.currentTimeMillis();
			int recentDeaths = 0;

			for (long deathTime : deathTimes) {
				if (currentTime - deathTime < 60000) { // Последняя минута
					recentDeaths++;
				}
			}

			return recentDeaths < MAX_DEATHS_PER_MINUTE;
		}

		public void recordDeath() {
			deathTimes[index] = System.currentTimeMillis();
			index = (index + 1) % MAX_DEATHS_PER_MINUTE;
		}
	}

	@Override
	public void onInitialize() {
		LOGGER.info("Restore Compass Mod initialized!");

		// Логируем смерть игрока и проверяем инвентарь
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (entity instanceof ServerPlayer player) {
				UUID playerUUID = player.getUUID();
				LOGGER.info("Player {} died at {}", player.getName().getString(), player.blockPosition());

				// Записываем смерть
				deathTrackers.computeIfAbsent(playerUUID, k -> new DeathTracker()).recordDeath();

				// Проверяем, были ли предметы в инвентаре
				boolean hasItems = checkInventoryForItems(player);

				// Сохраняем информацию
				hadItemsOnDeath.put(playerUUID, hasItems);

				if (!hasItems) {
					LOGGER.info("Player {} died with empty inventory", player.getName().getString());
				} else {
					LOGGER.info("Player {} died with items", player.getName().getString());
				}
			}
		});

		// Обработчик респауна после смерти
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			LOGGER.info("AFTER_RESPAWN triggered: player={}, alive={}",
					newPlayer.getName().getString(), alive);

			// alive = false означает что игрок умер и респаунился
			if (!alive) {
				handlePlayerRespawn(newPlayer);
			}

			// Проверяем изменение измерения при респауне
			if (alive && !oldPlayer.level().dimension().equals(newPlayer.level().dimension())) {
				LOGGER.info("Player {} changed dimension from {} to {}",
						newPlayer.getName().getString(),
						oldPlayer.level().dimension().location(),
						newPlayer.level().dimension().location());

				validateCompassForDimension(newPlayer);
			}
		});

		// Обработка отключения игрока - используем ServerPlayConnectionEvents
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayer player = handler.player;
			UUID playerUUID = player.getUUID();
			LOGGER.info("Player {} disconnected, cleaning up data", player.getName().getString());

			// Удаляем компас при выходе
			removeAllRestoreCompasses(player);

			// Очищаем данные сразу
			compassGivenPlayers.remove(playerUUID);
			hadItemsOnDeath.remove(playerUUID);
			playerDimensions.remove(playerUUID);

			// Планируем отложенную очистку трекера смертей
			ScheduledFuture<?> future = scheduler.schedule(() -> {
				deathTrackers.remove(playerUUID);
				scheduledCleanups.remove(playerUUID);
				LOGGER.debug("Cleaned up death tracker for {}", playerUUID);
			}, 5, TimeUnit.MINUTES);

			scheduledCleanups.put(playerUUID, future);
		});

		// При подключении игрока отменяем запланированную очистку
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			UUID playerUUID = handler.player.getUUID();
			ScheduledFuture<?> cleanup = scheduledCleanups.remove(playerUUID);
			if (cleanup != null && !cleanup.isDone()) {
				cleanup.cancel(false);
				LOGGER.debug("Cancelled cleanup for reconnected player {}", playerUUID);
			}
		});

		// Копирование данных при пересоздании игрока (краш/перезагрузка)
		ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
			UUID playerUUID = oldPlayer.getUUID();

			// Копируем данные о выданных компасах
			Long compassTime = compassGivenPlayers.get(playerUUID);
			if (compassTime != null) {
				compassGivenPlayers.put(newPlayer.getUUID(), compassTime);
			}

			// Копируем трекер смертей
			DeathTracker tracker = deathTrackers.get(playerUUID);
			if (tracker != null) {
				deathTrackers.put(newPlayer.getUUID(), tracker);
			}
		});

		// Проверка расстояния и владельца компаса каждые 20 тиков (1 секунда)
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			tickCounter++;
			if (tickCounter >= 20) {
				tickCounter = 0;

				for (ServerPlayer player : server.getPlayerList().getPlayers()) {
					// Проверяем и валидируем компасы
					checkAndValidateCompass(player);

					// Отслеживаем смену измерений
					ResourceKey<Level> currentDim = player.level().dimension();
					ResourceKey<Level> lastDim = playerDimensions.get(player.getUUID());

					if (lastDim != null && !lastDim.equals(currentDim)) {
						LOGGER.info("Player {} changed dimension from {} to {}",
								player.getName().getString(), lastDim.location(), currentDim.location());
						validateCompassForDimension(player);
					}

					playerDimensions.put(player.getUUID(), currentDim);
				}
			}

			// Очистка старых записей каждые 5 минут (6000 тиков)
			if (tickCounter % 6000 == 0) {
				cleanupOldData();
			}
		});
	}

	private void handlePlayerRespawn(ServerPlayer newPlayer) {
		UUID playerUUID = newPlayer.getUUID();

		// Проверяем трекер смертей
		DeathTracker tracker = deathTrackers.get(playerUUID);
		if (tracker != null && !tracker.canGiveCompass()) {
			newPlayer.sendSystemMessage(Component.literal("§cСлишком много смертей подряд! Подождите немного."));
			LOGGER.warn("Player {} died too many times, compass denied", newPlayer.getName().getString());
			hadItemsOnDeath.remove(playerUUID);
			return;
		}

		// Проверяем, были ли предметы при смерти
		Boolean hadItems = hadItemsOnDeath.get(playerUUID);
		if (hadItems != null && !hadItems) {
			newPlayer.sendSystemMessage(Component.literal("§7Вы умерли без предметов, компас не требуется."));
			LOGGER.info("Player {} respawned but had no items on death, skipping compass",
					newPlayer.getName().getString());
			hadItemsOnDeath.remove(playerUUID);
			return;
		}

		// Проверяем кулдаун
		Long lastGiven = compassGivenPlayers.get(playerUUID);
		long currentTime = System.currentTimeMillis();

		if (lastGiven != null && currentTime - lastGiven < COMPASS_COOLDOWN) {
			long remainingTime = (COMPASS_COOLDOWN - (currentTime - lastGiven)) / 1000;
			newPlayer.sendSystemMessage(Component.literal(
					"§eПодождите еще " + remainingTime + " секунд перед получением нового компаса"));
			LOGGER.warn("Compass on cooldown for {} ({} seconds remaining)",
					newPlayer.getName().getString(), remainingTime);
			return;
		}

		// Выдаем компас
		LOGGER.info("Giving recovery compass to {}", newPlayer.getName().getString());
		if (giveOrUpdateRestoreCompass(newPlayer)) {
			compassGivenPlayers.put(playerUUID, currentTime);
			newPlayer.sendSystemMessage(Component.literal("§6Компас восстановления добавлен в инвентарь"));
		}

		// Очищаем информацию о предметах
		hadItemsOnDeath.remove(playerUUID);
	}

	private boolean checkInventoryForItems(ServerPlayer player) {
		// Проверяем основной инвентарь
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (!stack.isEmpty() && !isRestoreCompass(stack)) {
				return true;
			}
		}

		// Проверяем броню
		for (ItemStack armorItem : player.getInventory().armor) {
			if (!armorItem.isEmpty()) {
				return true;
			}
		}

		// Проверяем оффхенд
		return !player.getInventory().offhand.get(0).isEmpty();
	}

	private boolean giveOrUpdateRestoreCompass(ServerPlayer player) {
		try {
			// Проверяем, нет ли уже компаса у игрока
			if (hasRestoreCompass(player)) {
				LOGGER.info("Player {} already has a recovery compass", player.getName().getString());
				return false;
			}

			// Удаляем старые компасы (на всякий случай)
			removeAllRestoreCompasses(player);

			// Создаем новый компас восстановления
			ItemStack compass = new ItemStack(Items.RECOVERY_COMPASS);

			// Устанавливаем имя
			compass.set(DataComponents.CUSTOM_NAME, Component.literal("§6Компас восстановления"));

			// Добавляем кастомный NBT тег с уникальным ID
			CompoundTag nbt = new CompoundTag();
			nbt.putBoolean(RESTORE_COMPASS_TAG, true);
			nbt.putLong("DeathTime", System.currentTimeMillis());
			nbt.putUUID("PlayerUUID", player.getUUID());
			nbt.putString("PlayerName", player.getName().getString());
			nbt.putString("DeathDimension", player.level().dimension().location().toString());
			compass.set(DataComponents.CUSTOM_DATA, CustomData.of(nbt));

			// Пытаемся положить в первый слот хотбара
			for (int i = 0; i < 9; i++) {
				if (player.getInventory().getItem(i).isEmpty()) {
					player.getInventory().setItem(i, compass);
					LOGGER.info("Successfully gave recovery compass to player {} in slot {}",
							player.getName().getString(), i);
					return true;
				}
			}

			// Если хотбар полон, ищем любой свободный слот
			for (int i = 9; i < player.getInventory().getContainerSize(); i++) {
				if (player.getInventory().getItem(i).isEmpty()) {
					player.getInventory().setItem(i, compass);
					LOGGER.info("Successfully gave recovery compass to player {} in slot {}",
							player.getName().getString(), i);
					player.sendSystemMessage(Component.literal("§eКомпас помещен в инвентарь (хотбар был полон)"));
					return true;
				}
			}

			// Если инвентарь полностью забит - не выдаем компас
			LOGGER.warn("Failed to give recovery compass to player {} - inventory is full",
					player.getName().getString());
			player.sendSystemMessage(Component.literal("§cНе удалось выдать компас - инвентарь полон!"));
			player.sendSystemMessage(Component.literal("§cОсвободите место и умрите снова."));
			return false;

		} catch (Exception e) {
			LOGGER.error("Error giving recovery compass to player", e);
			return false;
		}
	}

	private void checkAndValidateCompass(ServerPlayer player) {
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (isRestoreCompass(stack)) {
				CustomData data = stack.get(DataComponents.CUSTOM_DATA);
				if (data != null) {
					CompoundTag nbt = data.copyTag();
					UUID compassOwner = nbt.getUUID("PlayerUUID");

					// Проверяем владельца компаса
					if (!player.getUUID().equals(compassOwner)) {
						player.getInventory().setItem(i, ItemStack.EMPTY);
						player.sendSystemMessage(Component.literal(
								"§cЭтот компас принадлежит " + nbt.getString("PlayerName") + "!"));
						LOGGER.warn("Removed compass from {} - belonged to different player",
								player.getName().getString());
						continue;
					}

					// Проверяем расстояние до места смерти
					checkDistanceAndRemoveCompass(player, i);
				}
			}
		}
	}

	private void checkDistanceAndRemoveCompass(ServerPlayer player, int slot) {
		Optional<GlobalPos> lastDeathPos = player.getLastDeathLocation();
		if (lastDeathPos.isEmpty()) {
			return;
		}

		GlobalPos deathPos = lastDeathPos.get();

		// Проверяем, что игрок в том же измерении
		if (!player.level().dimension().equals(deathPos.dimension())) {
			return;
		}

		// Вычисляем расстояние до места смерти
		BlockPos deathBlockPos = deathPos.pos();
		double distance = player.position().distanceTo(deathBlockPos.getCenter());

		// Удаляем компас, если игрок близко к месту смерти
		if (distance <= MIN_REMOVAL_DISTANCE) {
			player.getInventory().setItem(slot, ItemStack.EMPTY);
			player.sendSystemMessage(Component.literal("§aВы достигли места смерти. Компас исчез."));
			LOGGER.info("Removed compass for player {} - reached death location", player.getName().getString());

			// Сбрасываем кулдаун для возможности получить новый компас
			compassGivenPlayers.remove(player.getUUID());
		}
	}

	private void validateCompassForDimension(ServerPlayer player) {
		Optional<GlobalPos> lastDeathPos = player.getLastDeathLocation();
		if (lastDeathPos.isEmpty()) {
			return;
		}

		// Если игрок сменил измерение, проверяем все компасы
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (isRestoreCompass(stack)) {
				CustomData data = stack.get(DataComponents.CUSTOM_DATA);
				if (data != null) {
					CompoundTag nbt = data.copyTag();
					String deathDimension = nbt.getString("DeathDimension");

					// Если измерение смерти не совпадает с текущим измерением места смерти
					if (!deathDimension.equals(lastDeathPos.get().dimension().location().toString())) {
						// Обновляем компас
						nbt.putString("DeathDimension", lastDeathPos.get().dimension().location().toString());
						stack.set(DataComponents.CUSTOM_DATA, CustomData.of(nbt));
						player.sendSystemMessage(Component.literal("§eКомпас обновлен для нового места смерти"));
					}
				}
			}
		}
	}

	private boolean hasRestoreCompass(ServerPlayer player) {
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			if (isRestoreCompass(player.getInventory().getItem(i))) {
				return true;
			}
		}
		return false;
	}

	private void removeAllRestoreCompasses(ServerPlayer player) {
		int removed = 0;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (isRestoreCompass(stack)) {
				player.getInventory().setItem(i, ItemStack.EMPTY);
				removed++;
			}
		}
		if (removed > 0) {
			LOGGER.info("Removed {} recovery compass(es) from player {}", removed, player.getName().getString());
		}
	}

	private void cleanupOldData() {
		long currentTime = System.currentTimeMillis();

		// Очищаем старые записи о выданных компасах
		compassGivenPlayers.entrySet().removeIf(entry ->
				currentTime - entry.getValue() > 300000); // 5 минут

		// Очищаем старые записи о предметах при смерти
		hadItemsOnDeath.clear();

		// Очищаем завершенные задачи
		scheduledCleanups.entrySet().removeIf(entry ->
				entry.getValue().isDone());

		LOGGER.debug("Cleaned up old tracking data");
	}

	public static boolean isRestoreCompass(ItemStack stack) {
		if (!stack.is(Items.RECOVERY_COMPASS)) return false;

		CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
		return customData != null && customData.copyTag().getBoolean(RESTORE_COMPASS_TAG);
	}
}