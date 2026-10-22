package net.shchoo.scafe.scafe.model;

/** What an equipped item is for: the weapon's colour hits monsters, the armour's colour takes their hits. */
public enum EquipSlot {
    WEAPON("weapon"), ARMOR("armor");

    public final String label;

    EquipSlot(String label) {
        this.label = label;
    }
}
