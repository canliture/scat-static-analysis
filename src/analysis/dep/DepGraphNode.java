package analysis.dep;

public abstract class DepGraphNode {

    public abstract String dotName();
    public abstract String comparableName();
    public abstract String dotNameShort();
    public abstract String dotNameVerbose(boolean isModelled);
}
