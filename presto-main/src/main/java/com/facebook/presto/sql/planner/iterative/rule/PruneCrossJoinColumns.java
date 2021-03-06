/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.planner.iterative.rule;

import com.facebook.presto.matching.Pattern;
import com.facebook.presto.sql.planner.iterative.Rule;
import com.facebook.presto.sql.planner.plan.JoinNode;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.ProjectNode;
import com.google.common.collect.ImmutableList;

import java.util.Optional;

import static com.facebook.presto.sql.planner.iterative.rule.Util.pruneInputs;
import static com.facebook.presto.sql.planner.iterative.rule.Util.restrictChildOutputs;

/**
 * Cross joins don't support output symbol selection, so push the project-off through the node.
 */
public class PruneCrossJoinColumns
        implements Rule
{
    private static final Pattern PATTERN = Pattern.typeOf(ProjectNode.class);

    @Override
    public Pattern getPattern()
    {
        return PATTERN;
    }

    @Override
    public Optional<PlanNode> apply(PlanNode node, Context context)
    {
        ProjectNode parent = (ProjectNode) node;

        PlanNode child = context.getLookup().resolve(parent.getSource());
        if (!(child instanceof JoinNode)) {
            return Optional.empty();
        }

        JoinNode joinNode = (JoinNode) child;
        if (!joinNode.isCrossJoin()) {
            return Optional.empty();
        }

        return pruneInputs(child.getOutputSymbols(), parent.getAssignments().getExpressions())
                .map(dependencies ->
                        parent.replaceChildren(ImmutableList.of(
                                restrictChildOutputs(context.getIdAllocator(), joinNode, dependencies, dependencies).get())));
    }
}
