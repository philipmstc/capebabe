package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.model.World;

import java.util.Random;

/**
 * The tutorial's world: always the same seed, nothing saved, discoveries scripted ({@link TutorialDiscovery},
 * paced by whatever {@link TutorialDiscovery.Pacing} it's given).
 */
public final class TutorialWorld {
    public static final long SEED = 20261027L;
    public static final String NAME = "Tutorial";

    private TutorialWorld() {}

    public static GameServer create() {
        return create(TutorialDiscovery.DEFAULT_PACING);
    }

    public static GameServer create(TutorialDiscovery.Pacing pacing) {
        GameServer server = new GameServer(new World(0, NAME, SEED));
        server.setDiscovery(new TutorialDiscovery(new Random(SEED), pacing));
        server.setRandom(new Random(SEED));
        return server;
    }

    /** The tutorial's script, to force stages or change the pacing while it runs. */
    public static TutorialDiscovery script(GameServer server) {
        return (TutorialDiscovery) server.discovery();
    }
}
