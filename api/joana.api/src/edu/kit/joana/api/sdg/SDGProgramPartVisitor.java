/**
 * This file is part of the Joana IFC project. It is developed at the
 * Programming Paradigms Group of the Karlsruhe Institute of Technology.
 *
 * For further details on licensing please read the information at
 * http://joana.ipd.kit.edu or contact the authors.
 */
package edu.kit.joana.api.sdg;

public abstract class SDGProgramPartVisitor<R, D> {

	protected abstract R visitClass(SDGClass cl, D data);

	protected abstract R visitAttribute(SDGAttribute a, D data);

	protected abstract R visitMethod(SDGMethod m, D data);

	protected abstract R visitParameter(SDGParameter p, D data);

	protected abstract R visitExit(SDGMethodExitNode e, D data);

	protected abstract R visitInstruction(SDGInstruction i, D data);

	protected abstract R visitPhi(SDGPhi phi, D data);
}
