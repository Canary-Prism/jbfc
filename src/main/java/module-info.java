module canaryprism.jbfc {
    requires info.picocli;
    uses canaryprism.jbfc.optimise.Optimisation;
    provides canaryprism.jbfc.optimise.Optimisation with
            canaryprism.jbfc.optimise.collapse.CollapseOptimisation,
            canaryprism.jbfc.optimise.flow.FlowOptimisation,
            canaryprism.jbfc.optimise.state.StateOptimisation;
}