/*
 * Copyright (c) 2014, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.core.regexp;

import org.jcodings.specific.ASCIIEncoding;
import org.jcodings.specific.USASCIIEncoding;
import org.truffleruby.Layouts;
import org.truffleruby.Log;
import org.truffleruby.core.regexp.InterpolatedRegexpNodeFactory.RegexpBuilderNodeGen;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.rope.RopeBuilder;
import org.truffleruby.core.rope.RopeOperations;
import org.truffleruby.core.string.StringOperations;
import org.truffleruby.language.RubyNode;
import org.truffleruby.language.dispatch.CallDispatchHeadNode;
import org.truffleruby.parser.BodyTranslator;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.object.DynamicObject;

public class InterpolatedRegexpNode extends RubyNode {

    @Children private final RubyNode[] children;
    @Child private CallDispatchHeadNode toS = CallDispatchHeadNode.create();
    @Child private RegexpBuilderNode builderNode;

    public InterpolatedRegexpNode(RubyNode[] children, RegexpOptions options) {
        this.children = children;
        builderNode = RegexpBuilderNode.create(options);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return builderNode.execute(executeChildren(frame));
    }

    @ExplodeLoop
    protected Rope[] executeChildren(VirtualFrame frame) {
        Rope[] values = new Rope[children.length];
        for (int i = 0; i < children.length; i++) {
            final Object value = children[i].execute(frame);
            values[i] = StringOperations.rope((DynamicObject) toS.call(frame, value, "to_s"));
        }
        return values;
    }

    @NodeChild(value = "ropes", type = RubyNode[].class)
    public static abstract class RegexpBuilderNode extends RubyNode {

        private final RegexpOptions options;

        public static RegexpBuilderNode create(RegexpOptions options) {
            return RegexpBuilderNodeGen.create(options, null);
        }

        public RegexpBuilderNode(RegexpOptions options) {
            this.options = options;
        }

        public abstract DynamicObject execute(Rope[] parts);

        @Specialization(guards = { "ropesMatch(cachedParts, parts)" }, limit = "getCacheLimit()")
        public DynamicObject executeFast(Rope[] parts,
                @Cached(value = "parts", dimensions = 1) Rope[] cachedParts,
                @Cached("createRegexp(cachedParts)") DynamicObject regexp) {
            return regexp;
        }

        @Specialization(replaces = "executeFast")
        public DynamicObject executeslow(Rope[] parts) {
            Log.notOptimizedOnce(Log.UNSTABLE_INTERPOLATED_REGEXP);
            return createRegexp(parts);
        }

        @ExplodeLoop
        protected boolean ropesMatch(Rope[] a, Rope[] b) {
            for (int i = 0; i < a.length; i++) {
                if (a[i] != b[i]) {
                    return false;
                }
            }
            return true;
        }

        @TruffleBoundary
        protected DynamicObject createRegexp(Rope[] strings) {
            final RopeBuilder preprocessed = ClassicRegexp.preprocessDRegexp(getContext(), strings, options);

            final DynamicObject regexp = RegexpNodes.createRubyRegexp(getContext(), this, coreLibrary().getRegexpFactory(),
                    RopeOperations.ropeFromByteList(preprocessed), options);

            if (options.isEncodingNone()) {
                final Rope source = Layouts.REGEXP.getSource(regexp);

                if (!BodyTranslator.all7Bit(preprocessed.getBytes())) {
                    Layouts.REGEXP.setSource(regexp, RopeOperations.withEncodingVerySlow(source, ASCIIEncoding.INSTANCE));
                } else {
                    Layouts.REGEXP.setSource(regexp, RopeOperations.withEncodingVerySlow(source, USASCIIEncoding.INSTANCE));
                }
            }

            return regexp;
        }

        protected int getCacheLimit() {
            return getContext().getOptions().DEFAULT_CACHE;
        }
    }
}
