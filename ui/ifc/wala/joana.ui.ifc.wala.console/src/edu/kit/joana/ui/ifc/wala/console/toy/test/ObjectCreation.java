/**
 * This file is part of the Joana IFC project. It is developed at the
 * Programming Paradigms Group of the Karlsruhe Institute of Technology.
 *
 * For further details on licensing please read the information at
 * http://joana.ipd.kit.edu or contact the authors.
 */
package edu.kit.joana.ui.ifc.wala.console.toy.test;

class Wrapper {

	String s;

	public Wrapper() {

	}

	public Wrapper(String s) {
		this.s = s;
	}
}

public class ObjectCreation {

	public static void main(String[] args) {
		String s = new String("foo");
		Wrapper w = new Wrapper(s);

		Wrapper w2 = new Wrapper();
		w2.s = new String("bar");
	}
}
