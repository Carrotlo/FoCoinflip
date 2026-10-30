package me.foesio.foCoinflip;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import me.foesio.foCoinflip.model.Coinflip;
import org.junit.jupiter.api.Test;

class CoinflipMatchingIndexTest {

    @Test
    void matchingFlipStillOccupiesCreatorIndex() throws ReflectiveOperationException {
        CoinflipManager manager = new CoinflipManager(null, null, null, null);
        UUID creatorId = UUID.randomUUID();
        Coinflip flip = new Coinflip(UUID.randomUUID(), creatorId, "Dealer", 100, 1L, true);
        activeById(manager).put(flip.getId(), flip);
        activeByCreator(manager).put(creatorId, flip.getId());

        assertTrue(flip.claimForMatch());
        assertTrue(manager.hasActiveFlip(creatorId));
        assertTrue(activeByCreator(manager).containsKey(creatorId));

        assertTrue(flip.reopen());
        assertTrue(flip.cancel());
        assertFalse(manager.hasActiveFlip(creatorId));
        assertFalse(activeByCreator(manager).containsKey(creatorId));
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, Coinflip> activeById(CoinflipManager manager) throws ReflectiveOperationException {
        Field field = CoinflipManager.class.getDeclaredField("activeById");
        field.setAccessible(true);
        return (Map<UUID, Coinflip>) field.get(manager);
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, UUID> activeByCreator(CoinflipManager manager) throws ReflectiveOperationException {
        Field field = CoinflipManager.class.getDeclaredField("activeByCreator");
        field.setAccessible(true);
        return (Map<UUID, UUID>) field.get(manager);
    }
}
