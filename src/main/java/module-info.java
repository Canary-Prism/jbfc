module canaryprism.jbfc {
    requires info.picocli;
    uses canaryprism.jbfc.optimise.Optimisation;
    
    exports canaryprism.jbfc;
    exports canaryprism.jbfc.bf;
    exports canaryprism.jbfc.optimise;
    exports canaryprism.jbfc.optimise.collapse;
    exports canaryprism.jbfc.optimise.flow;
    exports canaryprism.jbfc.optimise.state;
    
    provides canaryprism.jbfc.optimise.Optimisation with
            canaryprism.jbfc.optimise.collapse.CollapseOptimisation,
            canaryprism.jbfc.optimise.flow.FlowOptimisation,
            canaryprism.jbfc.optimise.state.StateOptimisation;
}