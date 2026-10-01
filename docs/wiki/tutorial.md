# Tutorial

[Home](home.md) · [Start here](home.md#start-here)

The tutorial is a set of short lessons that teach the editor in your own world, with the real tools. Each lesson takes a few minutes, and what it builds can be undone in one click at the end.

## Take a lesson

1. Open **Help > Tutorial**, or click **Start tutorial** on the Quick start card (**Help > Quick start** shows it again).
2. The Tutorial window lists the lessons in three groups, with a check mark on the ones you finished. Click a lesson to start it. **Resume** at the top continues the lesson in progress.
3. A small card appears at the top of the screen: the lesson's title, "Step 2 of 6" and one instruction such as "Press `1` to pick the Select tool". The thing to click is outlined on the screen.
4. Do what the card says. A step ticks itself off when the game sees you do it; steps that only explain have a **Next** button.
5. At the end, **Undo what this lesson made** takes back exactly the edits you made during the lesson, or **Keep it** keeps them. Then **Next lesson** or **Back to lessons**.

| Button | What it does |
|---|---|
| Back | Goes to the step before |
| Skip step | Goes on without doing the step |
| Exit | Pauses the lesson; **Resume** continues at the same step |
| Learn more | Opens this wiki at the matching page |

## The lessons

| Group | Lessons |
|---|---|
| Basics | Getting around · Menus and finding things · Select · Edit a selection · Undo and history · Keys and settings |
| Brushes | Terrain brushes · Weather brush · Paint and palettes · Mix patterns · Shapes · Symmetry |
| Building | Library · Flip upside down · Import and export · Generate, Extrude and Fluid · Scatter · Tinker · Builder mode |

Take them in order the first time: later lessons use what earlier ones taught.

The **Builder mode** lesson happens outside the editor, where the card doesn't show: each step says what to do out there and to come back; the step ticks off once you are back.

## On a server

A step whose tool you may not use can't tick off: **Skip step** goes on. What each lesson needs ([permissions](permissions.md)):

| Lesson | Needs |
|---|---|
| Edit a selection, Shapes, Generate, Extrude and Fluid, Tinker | `region` (Generate also `clipboard`) |
| Terrain brushes, Weather brush, Paint and palettes, Mix patterns, Symmetry | `brush` |
| Library, Flip upside down | `clipboard` (saving in the library: `library.write` or your own folder) |
| Import and export | `clipboard`, `schematic.export` (a dropped file: `schematic.import`) |
| Scatter | `scatter` |
| Builder mode | `builder`, and Creative mode |

## Tips

- Lessons build and dig for real: start on open ground you don't mind changing, or undo at the end.
- Progress is saved on your computer and survives restarts; finished lessons keep their check mark until you restart them.
- Key names in the card are your own bindings, so a rebound key is named as you have it.

## Related

- [Getting started](getting-started.md)
- [Finding things](finding-things.md)
- [Permissions](permissions.md)
- [Tools at a glance](tools.md)
