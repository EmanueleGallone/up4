/*
 SPDX-License-Identifier: Apache-2.0
 SPDX-FileCopyrightText: 2020-present Open Networking Foundation <info@opennetworking.org>
 */
package org.omecproject.up4.cli;

import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.omecproject.up4.impl.Up4AdminService;
import org.onlab.packet.Ip4Prefix;
import org.onosproject.cli.AbstractShellCommand;
import org.onosproject.net.behaviour.upf.UpfInterface;

import static org.omecproject.up4.impl.AppConstants.SLICE_MOBILE;

/**
 * UP4 UE IPv4 address pool insertion command.
 */
@Service
@Command(scope = "up4", name = "ue-pool-insert",
        description = "Insert an IPv4 pool prefix into the UP4 dataplane")
public class UePoolInsertCommand extends AbstractShellCommand {

    @Argument(index = 0, name = "ue-pool-prefix",
            description = "IPv4 Prefix of the UE pool",
            required = true)
    String poolPrefix = null;

    @Override
    protected void doExecute() throws Exception {
        Up4AdminService app = get(Up4AdminService.class);
        Ip4Prefix poolPrefix = Ip4Prefix.valueOf(this.poolPrefix);
        print("Adding UE IPv4 address pool prefix: %s", poolPrefix.toString());
        app.adminApply(UpfInterface.createUePoolFrom(poolPrefix, SLICE_MOBILE));
    }
}

