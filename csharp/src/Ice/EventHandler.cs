//
// Copyright (c) ZeroC, Inc. All rights reserved.
//

namespace IceInternal
{

public abstract class EventHandler
{
    //
    // Called to start a new asynchronous read or write operation.
    //
    abstract public bool startAsync(int op, AsyncCallback cb);

    abstract public bool finishAsync(int op);

    //
    // Called when there's a message ready to be processed.
    //
    abstract public void message(ThreadPoolCurrent op);

    //
    // Called when the event handler is unregistered.
    //
    abstract public void finished(ThreadPoolCurrent op);

    internal int _ready = 0;
    internal int _pending = 0;
    internal int _started = 0;
    internal bool _finish = false;

    internal bool _hasMoreData = false;
    internal int _registered = 0;
}

}
