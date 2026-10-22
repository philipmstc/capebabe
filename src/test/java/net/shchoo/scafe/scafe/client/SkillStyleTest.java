package net.shchoo.scafe.scafe.client;

import net.shchoo.scafe.scafe.protocol.Intent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillStyleTest {
    @Test
    void thePlayerPaletteMatchesWhatTheProtocolAllows() {
        assertEquals(Intent.PLAYER_COLORS, SkillStyle.PLAYER_COLORS.size(),
                "the server accepts exactly Intent.PLAYER_COLORS colours (change both, and bump PROTOCOL_VERSION)");
        assertEquals(SkillStyle.PLAYER_COLORS.size(), SkillStyle.PLAYER_COLOR_NAMES.size(), "a name for every colour");
    }
}
