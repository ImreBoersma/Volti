# UX laws

The [Laws of UX](https://lawsofux.com/) that change a decision in Volti, each turned into a rule. They explain *why* the rules in [android.md](android.md) and [messages.md](messages.md) look the way they do, and settle cases those don't cover.

The setting decides most of them: a musician at a stand, reading music, hands on an instrument, a glance at a time, sometimes in front of an audience.

## The reader is sacred

**Flow.** Once a score is open, nothing interrupts it: no dialogues, no prompts, no sync messages, no screen timeout. Anything that can wait, waits until the musician leaves the reader.

**Selective attention.** The eye is on the notes; everything else on screen competes with them. Controls over the score hide when not in use (the corner buttons go after 3 seconds) and use the reader's ink colour, not an accent.

**Peak-end rule.** The peak is the concert, the end is the last page. A glitch there outweighs any polish elsewhere. Test the last page turn, the half-page turn into the last page, and a score left open for two hours.

**Pareto principle.** Most of the use is two actions: open a score, turn a page. They get the speed budget and the testing first.

## Speed

**Doherty threshold (400 ms).** Anything that takes longer than 400 ms shows that it is working: `Checking Google Drive…`, `Downloading…`. A page turn is far under it (budget in `plan.md`) and shows nothing.

## Targets and reach

**Fitts's law.** The page turn is the biggest target there is: half the screen. Controls that are used mid-music sit where a hand reaches without looking (the annotation toolbar at the bottom edge). Rarely used controls go in corners, where they are easy to reach on purpose and hard to hit by accident. Never under 48 dp.

## Choices and memory

**Hick's law, choice overload.** Few options at a time. A few named pen colours, shown only while choosing one, not a colour picker; the symbols likewise. Settings only for what can't be decided for the musician.

**Miller's law, working memory, cognitive load.** Never make the musician remember something from another screen. Show the state where it matters: which ensemble, which part is mine, how fresh the sync is. One main task per screen.

**Chunking.** Long lists group: folders first, then files in natural order (`Part 2` before `Part 10`). Settings come in sections. A message is one or two short sentences.

**Tesler's law.** The complexity that can't be removed goes into the app, not to the musician. Volti works out the margins to crop, which parts are probably mine, which files changed; the musician confirms rather than configures.

**Serial position effect.** In a row of controls, the first and last are remembered best. The most used one goes first, the way out (`Done`, `Cancel`) last.

## Familiarity

**Jakob's law.** Volti works like the apps musicians already know: tap the right side for the next page as in e-readers and other score readers, standard Android Back, Material components with their usual behaviour.

**Mental model.** Use the musician's model, not the system's. An ensemble is "the band's shared folder", a file is "a part", "Your parts" are the ones you play. Storage, sync and identifiers never show (see [messages.md](messages.md)).

**Paradox of the active user.** Nobody reads a manual or an introduction. No onboarding screens; every control says what it does in its label, and a setting that needs more says so in one line of supporting text.

**Postel's law.** Accept what people give, in any reasonable form. The Drive field takes a full link or a bare folder id; search ignores case and accents. Output is always the same form.

## Grouping and emphasis

**Proximity, common region, similarity, uniform connectedness.** Things that belong together are together: the pen colours, eraser and undo in one panel with one border, the stamps in another. Elements that do the same have the same shape: all pen colours are the same circle.

**Law of Prägnanz.** Drawn glyphs are the simplest shape that reads: an arrow is three lines, a pencil two.

**Von Restorff effect.** One thing per screen stands out, and only if it matters. Red is for what is on or new (`New`, `Changed`, a selected chip), never decoration. If everything is emphasised, nothing is.

**Zeigarnik effect.** Unfinished things stay visible: a score that changed since I last opened it is marked `Changed` until I open it.

## A warning

**Aesthetic-usability effect.** A good-looking screen hides problems in a review. Judge a change on the tablet, on a stand, at arm's length, in rehearsal light, not on a laptop screenshot.

## Not used

Cognitive bias, Occam's razor, Parkinson's law and the goal-gradient effect describe people or reasoning in general and don't change a decision in Volti. Occam's razor is already in `CLAUDE.md` as "no speculative code".

## Not yet following this

Nothing known.
