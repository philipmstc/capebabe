package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.model.ContentBatch;

/** What a {@link GameServer} needs saved. Implemented in the persistence package; null storage = nothing saved. */
public interface Storage {

    /** One of something going straight into a character's saved inventory. */
    record Grant(int characterId, int productId, int count) {}

    /** Must be all-or-nothing: if this throws, the batch isn't applied or sent to anyone. */
    default void saveContent(ContentBatch batch) throws Exception {
        saveContent(batch, null);
    }

    /**
     * Saves the batch and (if not null) the grant in one go: both or neither. Used for one-of-a-kind drops, so a
     * crash can never leave a drop found but owned by nobody.
     */
    void saveContent(ContentBatch batch, Grant grant) throws Exception;

    /** The account's character in this world, created if it doesn't have one yet. */
    GameCharacter loadOrCreateCharacter(String accountName) throws Exception;

    void saveCharacter(GameCharacter character) throws Exception;
}
