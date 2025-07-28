# Brainfuck Java Compiler 

yes i read the title, yes i know it's actually called `jbfc`, fuck you and your precious acronyms

this was a very uhh confusing project to make

it started out as just a silly thing to code *something* and get it out of my system  
then i thought of adding optimisations like in [defuck](https://github.com/Canary-Prism/defuck)  
but the optimisations didn't seem to really do anything...

then i finally tried splitting up emitted bytecode into separate methods and then suddenly it was faster than the fastest
interpreter in defuck (FlowInterpreter) which made me really happy  
until i realised that that was the *only* optimisation that seemed to matter to HotSpot 
and regardless if i do `flow` level optimisations or `collapse` or even no further optimisations 
it'd run at roughly the same performance as long as i split the code into methods

whyyy

anyway here's a repo so y'all can probe at it or something

## Instructions

not like to use or anything- like you can probably figure that part out, picocli tells u all u need to know probably

this is for how instructions work in the compiler

there's a `canaryprism.jbfc.Instruction` interface that all instructions must implement 
and it defines methods for writing to code and writing to classes

the `writeCode()` method must be overridden and lets you append bytecode instructions that perform the actual code of the instruction

the `writeClass()` method can optionally be overridden and lets you add stuff to the class the file is compiling to

## Optimisations

optimisations apply different changes to what bytecode is emitted  
they have an identifier and you can specify which optimisation you want by identifier using the `-o` option

how optimisations work here (even though beyond splitting into methods nothing seems to matter grr)
is that each optimisation takes some subytpe of Instruction as an input and outputs some subtype of Instruction

you may define your own instructions that you output from your optimisation  
`BrainfuckInstruction` is the base set of instructions from the brainfuck "specs"

this way optimisations sorta "depend" on each other

for example, the `collapse` optimisation takes `BrainfuckInstruction` and outputs `CollapseInstruction`, 
and the `flow` optimisation takes `CollapseInstruction` and outputs `FlowInstruction`

to compile with the `flow` optimisation the compiler sends the instructions through `collapse` then through `flow` 
and writes the FlowInstructions to the class file

you make your own optimisations by implementing the `Optimisation<T, R>` SPI, you may then give the compiler access to them
by specifying the classpath or jar path by using the `-x` option

### `collapse`

collapse is one of the most basic optimisations, it just collapses consecutive +/-s and >/<s 

collapse does *not* promote loops to methods (mostly to make `flow` look good)

### `flow`

flow does more optimisations, like

- turn Loops where the total pointer offset is 0 with at least 1 modification of a different cell and the origin cell is modified by -1 into a Transfer instruction
- turn Loops where the total pointer offset is 0 with 0 modifications of different cells and the origin cell is modified by an odd number into a Set 0 instruction
- turn Loops where the total pointer offset is `n` with 0 modifications of any cell into a FindZero with step size of `n`
- promote all remaining true Loops into their own methods
- turn Modify `n` instructions that are immediately after Loop, Transfer, FindZero, or Set `k` instructions into Set `n (+ k)` instructions


### `state`

state does constant propagation, it keeps track of the array and notes which cells' values can be determined 
at compiletime (aka isn't influenced by data read from stdin) and if those cells are written to stdout 
it's converted to a static print instead

if the pointer is lost at any point then it immediately gives up constant propagation and further bytecode emitted
is identical to `flow`

idk why it's called `state` it's just that i tried to figure out constant propagation for defuck too and at that time
i decided to call it state for whatever stupid reason

this level takes much much longer to compile though especially for larger programs as it's basically a `flow` instruction interpreter bolted to a compiler