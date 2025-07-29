package canaryprism.jbfc.optimise.state;

import canaryprism.jbfc.optimise.Optimisation;
import canaryprism.jbfc.optimise.flow.FlowInstruction;

import java.util.*;
import java.util.function.Predicate;

public final class StateOptimisation implements Optimisation<FlowInstruction, StateInstruction> {
    
    @Override
    public Class<FlowInstruction> getInput() {
        return FlowInstruction.class;
    }
    
    @Override
    public Class<StateInstruction> getOutput() {
        return StateInstruction.class;
    }
    
    static class State {
        
        int[] array = new int[80_000];
        int pointer = 40_000;
        boolean pointer_infected = false;
        List<Predicate<Integer>> infection = new LinkedList<>();
        
        Map<Integer, Integer> modify_tracker = new HashMap<>();
        int pointer_modify_tracker = 0;
        
        boolean isPointerSafe() {
            return !pointer_infected;
        }
        
        void flushChanges(List<StateInstruction> instructions) {
            var changes = modify_tracker.entrySet()
                    .stream()
                    .filter((e) -> !isInfected(e.getKey()))
                    .map((e) -> new StateInstruction.BulkSet.Entry(e.getKey(), e.getValue()))
                    .toList();
            if (changes.isEmpty())
                return;
            instructions.add(new StateInstruction.BulkSet(changes));
            
            if (pointer_modify_tracker != 0) {
                instructions.add(new StateInstruction.PointerSet(pointer));
                pointer_modify_tracker = 0;
            }
            
            modify_tracker.clear();
        }
        
        void movePointer(int amount) {
            pointer += amount;
            pointer_modify_tracker += amount;
        }
        
        void setPointer(int value) {
            var last_pointer = pointer;
            pointer = value;
            pointer_modify_tracker += value - last_pointer;
        }
        
        void setHere(int value) {
            if (array[pointer] == value)
                return;
            modify_tracker.put(pointer, value);
            array[pointer] = value;
        }
        
        void modifyHere(int amount) {
            if (amount == 0)
                return;
            modify_tracker.compute(pointer, (_, v) -> (v != null) ? v + amount : amount);
            array[pointer] = (array[pointer] + amount) & 255;
        }
        
        boolean isInfectedHere() {
            return isInfected(this.pointer);
        }
        
        boolean isInfected(int index) {
            return infection.stream()
                    .anyMatch((e) -> e.test(index));
        }
        
        @Override
        public State clone()  {
            try {
                var o = ((State) super.clone());
                
                o.infection = new ArrayList<>(infection);
                
                o.modify_tracker = new HashMap<>(modify_tracker);
                
                return o;
                
            } catch (CloneNotSupportedException e) {
                throw new RuntimeException(e);
            }
        }
    }
    
    @Override
    public List<StateInstruction> optimise(List<FlowInstruction> input) {
        var state = new State();
        
        if (input.stream().anyMatch((e) -> e instanceof FlowInstruction.Read))
            return optimise(input, state, false);
        else
            return List.of(interpret(input));
    }
    
    List<StateInstruction> optimise(List<FlowInstruction> input, State state, boolean loop) {
//        counter.start(input);
        var output = new LinkedList<StateInstruction>();
        
        for (var e : input) {
            if (state.isPointerSafe()) {
                switch (e) {
                    case FlowInstruction.Read _ -> {
                        var pointer = state.pointer;
                        state.infection.add((i) -> i == pointer);
                        
                        state.flushChanges(output);
                        
                        output.add(StateInstruction.Read.INSTANCE);
                    }
                    case FlowInstruction.Write _ -> {
                        if (state.isInfectedHere()) {
                            output.add(StateInstruction.Write.INSTANCE);
                        } else {
                            if (output.peekLast() instanceof StateInstruction.Print(var list)) {
                                list.add(((char) state.array[state.pointer]));
                            } else {
                                output.add(new StateInstruction.Print(new LinkedList<>(List.of(((char) state.array[state.pointer])))));
                            }
                        }
                    }
                    case FlowInstruction.Move(var amount) -> state.movePointer(amount);
                    case FlowInstruction.FindZero(var step) -> {
                        var start_pointer = state.pointer;
                        while (!state.isInfectedHere() && state.array[state.pointer] != 0) {
                            state.movePointer(step);
                        }
                        if (state.isInfectedHere()) {
                            state.pointer_infected = true; // welp, no more state tracking :p
                            
                            state.setPointer(start_pointer);
                            state.flushChanges(output);
                            
                            output.add(new StateInstruction.FindZero(step));
                        }
                    }
                    case FlowInstruction.Modify(var amount) -> {
                        if (state.isInfectedHere()) {
                            output.add(new StateInstruction.Modify(amount));
                        } else {
                            state.modifyHere(amount);
                        }
                    }
                    case FlowInstruction.Set(var value) -> {
                        if (state.isInfectedHere()) {
                            output.add(new StateInstruction.Set(value));
                        } else {
                            state.setHere(value);
                        }
                    }
                    case FlowInstruction.Transfer(var targets) -> {
                        if (state.isInfectedHere()) {
                            state.flushChanges(output);
                            for (var target : targets) {
                                var offset = target.offset();
                                state.infection.add((i) -> i == offset);
                            }
                            output.add(new StateInstruction.Transfer(targets.stream()
                                    .map((target) -> new StateInstruction.Transfer.Target(target.offset(), target.multiplier()))
                                    .toList()));
                        } else if (state.array[state.pointer] == 0) {
                            continue;
                        }
                        
                        var value = state.array[state.pointer];
                        
                        for (var target : targets) {
                            state.movePointer(target.offset());
                            if (state.isInfectedHere()) {
                                state.flushChanges(output);
                                output.add(new StateInstruction.Modify(value * target.multiplier()));
                            } else {
                                state.modifyHere(value * target.multiplier());
                            }
                            state.movePointer(-target.offset());
                        }
                        
                        state.setHere(0);
                    
                    }
                    case FlowInstruction.Loop(var instructions, var _) -> {
                        if (state.isInfectedHere()) {
                            state.pointer_infected = true; // welp, no more state tracking :p
                            state.flushChanges(output);
                        } else if (state.array[state.pointer] == 0) {
                            continue;
                        }
                        if (state.isPointerSafe()) {
                            output.addAll(optimise(instructions, state, true));
                        } else {
                            output.add(new StateInstruction.Loop(optimise(instructions, state, false)));
                        }
                    }
                }
            } else {
                var instruction = switch (e) {
                    case FlowInstruction.Read _ -> StateInstruction.Read.INSTANCE;
                    case FlowInstruction.Write _ -> StateInstruction.Write.INSTANCE;
                    case FlowInstruction.Move(var amount) -> new StateInstruction.Move(amount);
                    case FlowInstruction.Modify(var amount) -> new StateInstruction.Modify(amount);
                    case FlowInstruction.Set(var value) -> new StateInstruction.Set(value);
                    case FlowInstruction.Transfer(var targets) -> new StateInstruction.Transfer(targets.stream()
                            .map((target) -> new StateInstruction.Transfer.Target(target.offset(), target.multiplier()))
                            .toList());
                    case FlowInstruction.Loop(var instructions, var _) -> new StateInstruction.Loop(optimise(instructions, state, true));
                    case FlowInstruction.FindZero(var step) -> new StateInstruction.FindZero(step);
                };
                output.add(instruction);
            }
        }
        
        if (state.isPointerSafe() && loop) {
//            if we got through an entire loop here without infecting the pointer,
//            we need to run the loop contents here more times
//            until the current cell becomes 0
//            to do this we just run this method more times until it is
//            **or**
//            worst case scenario we end up infecting the pointer here which means we'll have to redo this loop since
//            all the constant folding we *thought* we could do are actually invalid
        
            while (state.isPointerSafe() && state.array[state.pointer] != 0) {
//                see here we run it without loop because otherwise it'd also do this loop and that'd be bad hehe
                var instructions = optimise(input, state, false);
                if (state.isPointerSafe()) {
//                    yippee
                    output.addAll(instructions);
                } else {
//                    noooo
                    output.add(new StateInstruction.Loop(optimise(input, state, false)));
                }
            }
            
            if (!state.isPointerSafe()) {
//                nooooo
//                rerun this exact method, but now state has an infected pointer so it will just
//                convert the instructions without attempting any constant propagation
//                whether loop is true or false doesn't matter
                return optimise(input, state, false);
            }
//            woohoo i guess
        }
        return output;
    }
    
    StateInstruction.Print interpret(List<FlowInstruction> input) {
        return new StateInstruction.Print(interpret(input, new State()));
    }
    
    List<Character> interpret(List<FlowInstruction> input, State state) {
        var chars = new LinkedList<Character>();
        for (var e : input) {
            switch (e) {
                case FlowInstruction.Read _ -> throw new IllegalArgumentException();
                case FlowInstruction.Move(var amount) -> state.movePointer(amount);
                case FlowInstruction.Modify(var amount) -> state.modifyHere(amount);
                case FlowInstruction.Set(var value) -> state.setHere(value);
                case FlowInstruction.FindZero(var step) -> {
                    var pointer = state.pointer;
                    while (state.array[pointer] != 0) {
                        pointer += step;
                    }
                    state.setPointer(pointer);
                }
                case FlowInstruction.Transfer(var targets) -> {
                    var pointer = state.pointer;
                    var value = state.array[pointer];
                    for (var target : targets) {
                        state.setPointer(pointer + target.offset());
                        state.modifyHere(value * target.multiplier());
                    }
                    state.setPointer(pointer);
                    state.setHere(0);
                }
                case FlowInstruction.Loop(var instructions, var _) -> {
                    while (state.array[state.pointer] != 0)
                        chars.addAll(interpret(instructions, state));
                }
                case FlowInstruction.Write _ -> chars.add(((char) state.array[state.pointer]));
            }
        }
        return chars;
    }
    
    @Override
    public String getIdentifier() {
        return "state";
    }
}
