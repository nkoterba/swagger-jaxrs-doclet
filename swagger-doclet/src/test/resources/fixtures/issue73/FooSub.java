package fixtures.issue73;

import fixtures.issue69.*;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement(name = "foo")
@SuppressWarnings("javadoc")
public class FooSub extends fixtures.issue73.Foo {

	@XmlAttribute
	public long getId() {
		return -1;
	}
}
