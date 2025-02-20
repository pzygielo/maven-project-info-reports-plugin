/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.report.projectinfo.stubs;

import java.util.List;

import org.apache.maven.model.Contributor;
import org.apache.maven.model.Developer;

/**
 * @author <a href="mailto:vincent.siveton@gmail.com">Vincent Siveton</a>
 * @version $Id$
 */
public class TeamStub extends ProjectInfoProjectStub {
    @Override
    protected String getPOM() {
        return "team-plugin-config.xml";
    }

    @Override
    public List<Contributor> getContributors() {
        return getModel().getContributors();
    }

    @Override
    public List<Developer> getDevelopers() {
        return getModel().getDevelopers();
    }
}
