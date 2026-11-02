// The tutorial's guide (client/ui/Tutorial.java): what to do next, worked out from what you can see -- the tutorial
// world's server brings things on in order (TutorialDiscovery). Each step moves on by itself once you've done it;
// the explanations wait for "Got it". Finding a monster drop gets its own explanation, whenever it happens.

/** Two cells of a line that the page draws a picture over (a character nothing else uses, twice). */
export const PICTURE = '\uE000\uE000';

export const STEPS = ['EXPLORE', 'CRAFT', 'ITEM', 'KEEP_CRAFTING', 'SECONDARY', 'RECIPE', 'LINKS', 'WAIT_MONSTER', 'FIGHT',
  'WAIT_LOCKED', 'GOAL', 'WAIT_TERTIARY', 'WAYPOINT', 'DONE', 'FINISHED'];

export class Tutorial {
  constructor() {
    this.step = 'EXPLORE';
    this.dropShowing = false;
    this.dropExplained = false;
  }

  /** Moves on if what the step asked for is done; {@code screen}: { skillPageOpen, followedLink }. */
  update(game, screen) {
    if (!this.dropExplained && game.trophies() > 0) {
      this.dropExplained = true;
      this.dropShowing = true;
    }
    const c = game.content;
    switch (this.step) {
      case 'EXPLORE': if (c.nodes.size) this.step = 'CRAFT'; break;
      case 'CRAFT': if (firstItem(game, 'PRIMARY')) this.step = 'ITEM'; break;
      case 'KEEP_CRAFTING': if (skillOf(game, 'SECONDARY')) this.step = 'SECONDARY'; break;
      case 'SECONDARY': if (firstItem(game, 'SECONDARY')) this.step = 'RECIPE'; break;
      case 'RECIPE': if (screen.skillPageOpen) this.step = 'LINKS'; break;
      case 'LINKS': if (screen.followedLink) this.step = 'WAIT_MONSTER'; break;
      case 'WAIT_MONSTER': if (c.monsters.length) this.step = 'FIGHT'; break;
      case 'FIGHT': if ((game.stats.get('MONSTERS_KILLED') || 0) > 0) this.step = 'WAIT_LOCKED'; break;
      case 'WAIT_LOCKED': if (lockedNode(game)) this.step = 'GOAL'; break;
      case 'GOAL': if (game.goals.size) this.step = 'WAIT_TERTIARY'; break;
      case 'WAIT_TERTIARY': if (skillOf(game, 'TERTIARY')) this.step = 'WAYPOINT'; break;
      case 'WAYPOINT': if (game.waypoints.some(n => n.recipe.skill.type === 'SECONDARY')) this.step = 'DONE'; break;
    }
  }

  /** Whether what's showing waits for "Got it" (an explanation, not something to do). */
  dismissable() {
    return this.dropShowing || this.step === 'ITEM' || this.step === 'LINKS' || this.step === 'DONE';
  }

  dismiss() {
    if (this.dropShowing) { this.dropShowing = false; return; }
    this.step = { ITEM: 'KEEP_CRAFTING', LINKS: 'WAIT_MONSTER', DONE: 'FINISHED' }[this.step] || this.step;
  }

  /** The skill whose picture stands in for PICTURE in the lines to show now. */
  pictured(game) {
    const item = firstItem(game, 'PRIMARY'), recipe = item && game.content.madeBy(item);
    return recipe ? recipe.skill : null;
  }

  /** The title and lines to show now (empty once it's over). */
  text(game) {
    if (this.dropShowing) {
      return ['A monster drop!',
        'Monster drops get less unique but rarer.',
        'Every one you find is a trophy: put it on display at a Gym for a slot in your inventory, or for 5,000 attacks of Gym membership.'];
    }
    const primarySkill = skillOf(game, 'PRIMARY'), secondarySkill = skillOf(game, 'SECONDARY');
    const primary = primarySkill ? primarySkill.name : 'a skill';
    const secondary = secondarySkill ? secondarySkill.name : 'a secondary skill';
    switch (this.step) {
      case 'EXPLORE': return ['Welcome to capebabe!',
        'The world starts empty and grows as you play. Walk around (w a s d, or click the map) to explore until you discover something.'];
      case 'CRAFT': return ['You found a node!',
        'Click it to walk up and craft there -- or face it and press q, or right-click it and pick Craft -- to make an item.'];
      case 'ITEM': {
        const item = firstItem(game, 'PRIMARY');
        if (!item) return [];
        return ['Your first item! In your inventory (left):',
          ' ' + PICTURE + '     <-- the skill that makes it (' + primary + ').',
          '.' + PICTURE + ':    <-- dots either side are the item\'s tier: none at tier 0, up to :' + PICTURE
            + ': at tier 4. Higher tiers need a higher level to make, but are more useful.',
          'x' + Math.min(game.inventory.get(item) || 0, game.stackSize(item)) + '    <-- how many in this stack. A stack holds as many as your level in that skill, so it grows as you level up.'];
      }
      case 'KEEP_CRAFTING': return ['Keep crafting.', 'Making things levels you up -- and levelling up turns up new skills and nodes.'];
      case 'SECONDARY': return [secondary + ': a secondary skill',
        'Its node uses your ' + primary + ' items: each craft takes some and makes ' + secondary + '\'s item. Find its node and craft at it.'];
      case 'RECIPE': return ['Recipes',
        'Open the Skills tab (press 1, or click it) and click ' + secondary + ' to see its page: its recipe, what it\'s used for -- and under [details], the level each tier unlocks at.'];
      case 'LINKS': return ['Getting around',
        'On a skill\'s page every picture is a link: click one to jump to that skill\'s page. [\u2190] (or Esc) goes back the way you came; clicking Skills again goes back to the list.'];
      case 'WAIT_MONSTER': case 'WAIT_LOCKED': case 'WAIT_TERTIARY':
        return ['Keep going.', 'Explore and craft: there\'s more to find.'];
      case 'FIGHT': return ['A monster!',
        'Click it to fight it (or right-click it and pick Fight, or stand next to it and press f). Fighting trains combat; weapons and armour (equip from the inventory) help.'];
      case 'GOAL': {
        const node = lockedNode(game);
        if (!node) return [];
        const skill = node.recipe.skill;
        return ['Too high level, for now',
          'That ' + node.recipe.product.name + ' node needs ' + skill.name + ' Lv. ' + node.recipe.levelNeeded + '. Walk up to it and press q to try it, then g to make it a goal -- '
            + 'or open ' + skill.name + '\'s page (Skills tab), then [details], and click its goal line. Your goal shows in the xp tracker at the top.'];
      }
      case 'WAYPOINT': {
        const tertiary = skillOf(game, 'TERTIARY');
        if (!tertiary || !tertiary.line) return [];
        const first = tertiary.line.recipes[0];
        const maker = first.components.map(k => game.content.madeBy(k.product)).find(r => r && r.skill.type === 'SECONDARY');
        const component = maker ? maker.product.name : 'its secondary item';
        return [tertiary.name + ': a tertiary skill',
          'Its recipe takes a secondary skill\'s item too: ' + component + '. Waypoints point you at the nearest node for something: open '
            + tertiary.name + '\'s page, click ' + component + '\'s skill in its recipe, then [details], and click its tier to set a waypoint to ' + component + '.'];
      }
      case 'DONE': return ['That\'s the tutorial!',
        'This world is yours to keep exploring. When you\'re ready, log out (top right) to go back to the login and join a real world.'];
      default: return [];
    }
  }
}

function skillOf(game, type) {
  return game.content.craftingSkills().find(s => s.type === type) || null;
}

function firstItem(game, type) {
  for (const [item, n] of game.inventory) {
    if (n <= 0) continue;
    const recipe = game.content.madeBy(item);
    if (recipe && recipe.skill.type === type) return item;
  }
  return null;
}

function lockedNode(game) {
  for (const node of game.content.nodes.values()) if (game.lockedFor(node)) return node;
  return null;
}

