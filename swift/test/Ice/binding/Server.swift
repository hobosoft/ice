//
// Copyright (c) ZeroC, Inc. All rights reserved.
//

import Dispatch
import Ice
import TestCommon

class Server: TestHelperI {
    public override func run(args: [String]) throws {
        let communicator = try initialize(args)
        defer {
            communicator.destroy()
        }

        communicator.getProperties().setProperty(key: "TestAdapter.Endpoints",
                                                 value: getTestEndpoint(num: 0))
        let adapter = try communicator.createObjectAdapter("TestAdapter")
        try adapter.add(servant: RemoteCommunicatorDisp(RemoteCommunicatorI(helper: self)),
                        id: Ice.stringToIdentity("communicator"))
        try adapter.activate()
        serverReady()
        communicator.waitForShutdown()
    }
}
