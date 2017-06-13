package abstract_modules;

import java.io.Closeable;

public abstract class Action<TArg, TReturn> implements Closeable {
	public abstract TReturn run(TArg arg) throws Exception;
}
