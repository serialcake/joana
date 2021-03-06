/**
 * This file is part of the Joana IFC project. It is developed at the
 * Programming Paradigms Group of the Karlsruhe Institute of Technology.
 *
 * For further details on licensing please read the information at
 * http://joana.ipd.kit.edu or contact the authors.
 */
package edu.kit.joana.ui.ifc.wala.console.toy.test;

public class LeakByPrintintIntStatic {

	private static IntSecret sec = new IntSecret(42);

	public static void main(String[] args) {
		PrintLeakerInt leaker = new PrintLeakerInt(System.out);
		leaker.leak(sec);

	}
}
