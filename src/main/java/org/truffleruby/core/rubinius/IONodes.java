/*
 * Copyright (c) 2015, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 *
 * Some of the code in this class is transliterated from C++ code in Rubinius.
 *
 * Copyright (c) 2007-2014, Evan Phoenix and contributors
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright notice
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 * * Neither the name of Rubinius nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 1.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2007, 2008 Ola Bini <ola@ologix.com>
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the EPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the EPL, the GPL or the LGPL.
 */
package org.truffleruby.core.rubinius;

import static org.truffleruby.core.string.StringOperations.rope;

import java.nio.ByteBuffer;

import org.truffleruby.Layouts;
import org.truffleruby.builtins.CallerFrameAccess;
import org.truffleruby.builtins.CoreClass;
import org.truffleruby.builtins.CoreMethod;
import org.truffleruby.builtins.CoreMethodArrayArgumentsNode;
import org.truffleruby.builtins.NonStandard;
import org.truffleruby.builtins.Primitive;
import org.truffleruby.builtins.PrimitiveArrayArgumentsNode;
import org.truffleruby.builtins.UnaryCoreMethodNode;
import org.truffleruby.core.array.ArrayOperations;
import org.truffleruby.core.array.ArrayUtils;
import org.truffleruby.core.regexp.RegexpNodes.RegexpSetLastMatchPrimitiveNode;
import org.truffleruby.core.regexp.RegexpNodesFactory.RegexpSetLastMatchPrimitiveNodeFactory;
import org.truffleruby.core.rope.BytesVisitor;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.rope.RopeBuilder;
import org.truffleruby.core.rope.RopeConstants;
import org.truffleruby.core.rope.RopeOperations;
import org.truffleruby.core.string.StringOperations;
import org.truffleruby.language.RubyGuards;
import org.truffleruby.language.Visibility;
import org.truffleruby.language.arguments.ReadCallerFrameNode;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.language.dispatch.CallDispatchHeadNode;
import org.truffleruby.language.objects.AllocateObjectNode;
import org.truffleruby.language.threadlocal.FindThreadAndFrameLocalStorageNode;
import org.truffleruby.language.threadlocal.FindThreadAndFrameLocalStorageNodeGen;
import org.truffleruby.language.threadlocal.ThreadAndFrameLocalStorage;
import org.truffleruby.platform.FDSet;
import org.truffleruby.platform.Platform;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.BranchProfile;

import jnr.constants.platform.Errno;
import jnr.constants.platform.Fcntl;
import jnr.constants.platform.OpenFlags;
import jnr.posix.DefaultNativeTimeval;
import jnr.posix.Timeval;
import org.truffleruby.extra.ffi.Pointer;

@CoreClass("IO")
public abstract class IONodes {

    private static final int CLOSED_FD = -1;
    public static final String LAST_LINE_VARIABLE = "$_";

    public static abstract class IOPrimitiveArrayArgumentsNode extends PrimitiveArrayArgumentsNode {

        private final BranchProfile errorProfile = BranchProfile.create();

        protected int ensureSuccessful(int result, int errno, String extra) {
            assert result >= -1;
            if (result == -1) {
                errorProfile.enter();
                throw new RaiseException(coreExceptions().errnoError(errno, extra, this));
            }
            return result;
        }

        protected int ensureSuccessful(int result) {
            return ensureSuccessful(result, posix().errno(), "");
        }

        protected int ensureSuccessful(int result, String extra) {
            return ensureSuccessful(result, posix().errno(), " - " + extra);
        }
    }

    @CoreMethod(names = "__allocate__", constructor = true, visibility = Visibility.PRIVATE)
    public static abstract class AllocateNode extends UnaryCoreMethodNode {

        @Child private CallDispatchHeadNode newBufferNode = CallDispatchHeadNode.create();
        @Child private AllocateObjectNode allocateNode = AllocateObjectNode.create();

        @Specialization
        public DynamicObject allocate(VirtualFrame frame, DynamicObject classToAllocate) {
            final DynamicObject buffer = (DynamicObject) newBufferNode.call(frame, coreLibrary().getInternalBufferClass(), "new");
            return allocateNode.allocate(classToAllocate, buffer, 0, CLOSED_FD, 0);
        }

    }

    @Primitive(name = "io_connect_pipe", needsSelf = false)
    public static abstract class IOConnectPipeNode extends IOPrimitiveArrayArgumentsNode {

        @CompilationFinal private int RDONLY = -1;
        @CompilationFinal private int WRONLY = -1;

        @Specialization
        public boolean connectPipe(DynamicObject lhs, DynamicObject rhs) {
            final int[] fds = new int[2];

            ensureSuccessful(posix().pipe(fds));

            newOpenFd(fds[0]);
            newOpenFd(fds[1]);

            Layouts.IO.setDescriptor(lhs, fds[0]);
            Layouts.IO.setMode(lhs, getRDONLY());

            Layouts.IO.setDescriptor(rhs, fds[1]);
            Layouts.IO.setMode(rhs, getWRONLY());

            return true;
        }

        @TruffleBoundary(transferToInterpreterOnException = false)
        private void newOpenFd(int newFd) {
            final int FD_CLOEXEC = 1;

            if (newFd > 2) {
                final int flags = ensureSuccessful(posix().fcntl(newFd, Fcntl.F_GETFD));
                ensureSuccessful(posix().fcntlInt(newFd, Fcntl.F_SETFD, flags | FD_CLOEXEC));
            }
        }

        private int getRDONLY() {
            if (RDONLY == -1) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                RDONLY = (int) getContext().getNativePlatform().getRubiniusConfiguration().get("rbx.platform.file.O_RDONLY");
            }

            return RDONLY;
        }

        private int getWRONLY() {
            if (WRONLY == -1) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                WRONLY = (int) getContext().getNativePlatform().getRubiniusConfiguration().get("rbx.platform.file.O_WRONLY");
            }

            return WRONLY;
        }

    }

    @Primitive(name = "io_open", needsSelf = false, lowerFixnum = {2, 3})
    public static abstract class IOOpenPrimitiveNode extends IOPrimitiveArrayArgumentsNode {

        @TruffleBoundary(transferToInterpreterOnException = false)
        @Specialization(guards = "isRubyString(path)")
        public int open(DynamicObject path, int mode, int permission) {
            String pathString = StringOperations.getString(path);
            int fd = posix().open(pathString, mode, permission);
            if (fd == -1) {
                ensureSuccessful(fd, pathString);
            }
            return fd;
        }

    }

    @Primitive(name = "file_truncate", needsSelf = false)
    public static abstract class FileTruncatePrimitiveNode extends IOPrimitiveArrayArgumentsNode {

        @TruffleBoundary(transferToInterpreterOnException = false)
        @Specialization(guards = "isRubyString(path)")
        public int truncate(DynamicObject path, long length) {
            return ensureSuccessful(posix().truncate(StringOperations.getString(path), length));
        }

    }

    @Primitive(name = "file_ftruncate")
    public static abstract class FileFTruncatePrimitiveNode extends IOPrimitiveArrayArgumentsNode {

        @TruffleBoundary(transferToInterpreterOnException = false)
        @Specialization
        public int ftruncate(DynamicObject file, long length) {
            final int fd = Layouts.IO.getDescriptor(file);
            return ensureSuccessful(posix().ftruncate(fd, length));
        }

    }

    @Primitive(name = "file_fnmatch", needsSelf = false, lowerFixnum = 3)
    public static abstract class FileFNMatchPrimitiveNode extends IOPrimitiveArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = { "isRubyString(pattern)", "isRubyString(path)" })
        public boolean fnmatch(DynamicObject pattern, DynamicObject path, int flags) {
            final Rope patternRope = rope(pattern);
            final Rope pathRope = rope(path);

            return fnmatch(patternRope.getBytes(),
                    0,
                    patternRope.byteLength(),
                    pathRope.getBytes(),
                    0,
                    pathRope.byteLength(),
                    flags) != FNM_NOMATCH;
        }


        private final static boolean DOSISH = Platform.IS_WINDOWS;

        private final static int FNM_NOESCAPE = 0x01;
        private final static int FNM_PATHNAME = 0x02;
        private final static int FNM_DOTMATCH = 0x04;
        private final static int FNM_CASEFOLD = 0x08;

        public final static int FNM_NOMATCH = 1;

        private static boolean isdirsep(char c) {
            return c == '/' || DOSISH && c == '\\';
        }

        private static boolean isdirsep(byte c) {
            return isdirsep((char)(c & 0xFF));
        }

        private static int rb_path_next(byte[] _s, int s, int send) {
            while(s < send && !isdirsep(_s[s])) {
                s++;
            }
            return s;
        }

        @SuppressWarnings("fallthrough")
        private static int fnmatch_helper(byte[] bytes, int pstart, int pend, byte[] string, int sstart, int send, int flags) {
            char test;
            int s = sstart;
            int pat = pstart;
            boolean escape = (flags & FNM_NOESCAPE) == 0;
            boolean pathname = (flags & FNM_PATHNAME) != 0;
            boolean period = (flags & FNM_DOTMATCH) == 0;
            boolean nocase = (flags & FNM_CASEFOLD) != 0;

            while(pat<pend) {
                char c = (char)(bytes[pat++] & 0xFF);
                switch(c) {
                    case '?':
                        if(s >= send || (pathname && isdirsep(string[s])) ||
                                (period && string[s] == '.' && (s == 0 || (pathname && isdirsep(string[s-1]))))) {
                            return FNM_NOMATCH;
                        }
                        s++;
                        break;
                    case '*':
                        while(pat < pend && (c = (char)(bytes[pat++] & 0xFF)) == '*') {}
                        if(s < send && (period && string[s] == '.' && (s == 0 || (pathname && isdirsep(string[s-1]))))) {
                            return FNM_NOMATCH;
                        }
                        if(pat > pend || (pat == pend && c == '*')) {
                            if(pathname && rb_path_next(string, s, send) < send) {
                                return FNM_NOMATCH;
                            } else {
                                return 0;
                            }
                        } else if((pathname && isdirsep(c))) {
                            s = rb_path_next(string, s, send);
                            if(s < send) {
                                s++;
                                break;
                            }
                            return FNM_NOMATCH;
                        }
                        test = (char)(escape && c == '\\' && pat < pend ? (bytes[pat] & 0xFF) : c);
                        test = Character.toLowerCase(test);
                        pat--;
                        while(s < send) {
                            if((c == '?' || c == '[' || Character.toLowerCase((char) string[s]) == test) &&
                                    fnmatch(bytes, pat, pend, string, s, send, flags | FNM_DOTMATCH) == 0) {
                                return 0;
                            } else if((pathname && isdirsep(string[s]))) {
                                break;
                            }
                            s++;
                        }
                        return FNM_NOMATCH;
                    case '[':
                        if(s >= send || (pathname && isdirsep(string[s]) ||
                                (period && string[s] == '.' && (s == 0 || (pathname && isdirsep(string[s-1])))))) {
                            return FNM_NOMATCH;
                        }
                        pat = range(bytes, pat, pend, (char)(string[s]&0xFF), flags);
                        if(pat == -1) {
                            return FNM_NOMATCH;
                        }
                        s++;
                        break;
                    case '\\':
                        if (escape) {
                            if (pat >= pend) {
                                c = '\\';
                            } else {
                                c = (char)(bytes[pat++] & 0xFF);
                            }
                        }
                    default:
                        if(s >= send) {
                            return FNM_NOMATCH;
                        }
                        if(DOSISH && (pathname && isdirsep(c) && isdirsep(string[s]))) {
                        } else {
                            if (nocase) {
                                if(Character.toLowerCase(c) != Character.toLowerCase((char)string[s])) {
                                    return FNM_NOMATCH;
                                }

                            } else {
                                if(c != (char)(string[s] & 0xFF)) {
                                    return FNM_NOMATCH;
                                }
                            }

                        }
                        s++;
                        break;
                }
            }
            return s >= send ? 0 : FNM_NOMATCH;
        }

        public static int fnmatch(
                byte[] bytes, int pstart, int pend,
                byte[] string, int sstart, int send, int flags) {

            // This method handles '**/' patterns and delegates to
            // fnmatch_helper for the main work.

            boolean period = (flags & FNM_DOTMATCH) == 0;
            boolean pathname = (flags & FNM_PATHNAME) != 0;

            int pat_pos = pstart;
            int str_pos = sstart;
            int ptmp = -1;
            int stmp = -1;

            if (pathname) {
                while (true) {
                    if (isDoubleStarAndSlash(bytes, pat_pos)) {
                        do { pat_pos += 3; } while (isDoubleStarAndSlash(bytes, pat_pos));
                        ptmp = pat_pos;
                        stmp = str_pos;
                    }

                    int patSlashIdx = nextSlashIndex(bytes, pat_pos, pend);
                    int strSlashIdx = nextSlashIndex(string, str_pos, send);

                    if (fnmatch_helper(bytes, pat_pos, patSlashIdx,
                            string, str_pos, strSlashIdx, flags) == 0) {
                        if (patSlashIdx < pend && strSlashIdx < send) {
                            pat_pos = ++patSlashIdx;
                            str_pos = ++strSlashIdx;
                            continue;
                        }
                        if (patSlashIdx == pend && strSlashIdx == send) {
                            return 0;
                        }
                    }
                /* failed : try next recursion */
                    if (ptmp != -1 && stmp != -1 && !(period && string[stmp] == '.')) {
                        stmp = nextSlashIndex(string, stmp, send);
                        if (stmp < send) {
                            pat_pos = ptmp;
                            stmp++;
                            str_pos = stmp;
                            continue;
                        }
                    }
                    return FNM_NOMATCH;
                }
            } else {
                return fnmatch_helper(bytes, pstart, pend, string, sstart, send, flags);
            }

        }

        // are we at '**/'
        private static boolean isDoubleStarAndSlash(byte[] bytes, int pos) {
            if ((bytes.length - pos) <= 2) {
                return false; // not enough bytes
            }

            return bytes[pos] == '*'
                    && bytes[pos + 1] == '*'
                    && bytes[pos + 2] == '/';
        }

        // Look for slash, starting from 'start' position, until 'end'.
        private static int nextSlashIndex(byte[] bytes, int start, int end) {
            int idx = start;
            while (idx < end && idx < bytes.length && bytes[idx] != '/') {
                idx++;
            }
            return idx;
        }

        private static int range(byte[] _pat, int pat, int pend, char test, int flags) {
            boolean not;
            boolean ok = false;
            boolean nocase = (flags & FNM_CASEFOLD) != 0;
            boolean escape = (flags & FNM_NOESCAPE) == 0;

            not = _pat[pat] == '!' || _pat[pat] == '^';
            if(not) {
                pat++;
            }

            if (nocase) {
                test = Character.toLowerCase(test);
            }

            while(_pat[pat] != ']') {
                char cstart, cend;
                if(escape && _pat[pat] == '\\') {
                    pat++;
                }
                if(pat >= pend) {
                    return -1;
                }
                cstart = cend = (char)(_pat[pat++]&0xFF);
                if(_pat[pat] == '-' && _pat[pat+1] != ']') {
                    pat++;
                    if(escape && _pat[pat] == '\\') {
                        pat++;
                    }
                    if(pat >= pend) {
                        return -1;
                    }

                    cend = (char)(_pat[pat++] & 0xFF);
                }

                if (nocase) {
                    if (Character.toLowerCase(cstart) <= test
                            && test <= Character.toLowerCase(cend)) {
                        ok = true;
                    }
                } else {
                    if (cstart <= test && test <= cend) {
                        ok = true;
                    }
                }
            }

            return ok == not ? -1 : pat + 1;
        }

    }

    @NonStandard
    @CoreMethod(names = "ensure_open", visibility = Visibility.PRIVATE)
    public static abstract class IOEnsureOpenPrimitiveNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public DynamicObject ensureOpen(DynamicObject file,
                @Cached("create()") BranchProfile errorProfile) {
            // TODO BJF 13-May-2015 Handle nil case
            final int fd = Layouts.IO.getDescriptor(file);
            if (fd == CLOSED_FD) {
                errorProfile.enter();
                throw new RaiseException(coreExceptions().ioError("closed stream", this));
            } else if (fd == -2) {
                errorProfile.enter();
                throw new RaiseException(coreExceptions().ioError("shutdown stream", this));
            }
            return nil();
        }

    }


    @NonStandard
    @CoreMethod(names = "socket_recv", required = 3, lowerFixnum = { 1, 2, 3 }, visibility = Visibility.PRIVATE)
    public static abstract class IOSocketReadNode extends IOPrimitiveArrayArgumentsNode {

        @TruffleBoundary(transferToInterpreterOnException = false)
        @Specialization
        public Object socketRead(DynamicObject io, int length, int flags, int type) {
            final int sockfd = Layouts.IO.getDescriptor(io);

            if (type != 0) {
                throw new UnsupportedOperationException();
            }

            final ByteBuffer buffer = ByteBuffer.allocate(length);
            final int bytesRead = getContext().getThreadManager().runBlockingSystemCallUntilResult(this,
                    () -> nativeSockets().recvfrom(sockfd, buffer, length, flags, Pointer.JNR_NULL, Pointer.JNR_NULL));
            ensureSuccessful(bytesRead);
            buffer.position(bytesRead);

            return createString(RopeBuilder.createRopeBuilder(buffer.array(), buffer.arrayOffset(), buffer.position()));
        }

    }

    @Primitive(name = "io_read_if_available", lowerFixnum = 1)
    public static abstract class IOReadIfAvailableNode extends IOPrimitiveArrayArgumentsNode {

        @TruffleBoundary(transferToInterpreterOnException = false)
        @Specialization
        public Object readIfAvailable(DynamicObject file, int numberOfBytes) {
            // Taken from Rubinius's IO::read_if_available.

            if (numberOfBytes == 0) {
                return createString(RopeConstants.EMPTY_ASCII_8BIT_ROPE);
            }

            final int fd = Layouts.IO.getDescriptor(file);

            try (FDSet fdSet = getContext().getNativePlatform().createFDSet()) {
                fdSet.set(fd);

                final Timeval timeoutObject = new DefaultNativeTimeval(jnr.ffi.Runtime.getSystemRuntime());
                timeoutObject.setTime(new long[]{ 0, 0 });

                final int res = ensureSuccessful(nativeSockets().select(fd + 1, fdSet.getPointer(),
                        Pointer.JNR_NULL, Pointer.JNR_NULL, timeoutObject));

                if (res == 0) {
                    throw new RaiseException(coreExceptions().eAGAINWaitReadable(this));
                }
            }

            final byte[] bytes = new byte[numberOfBytes];
            final int bytesRead = ensureSuccessful(posix().read(fd, bytes, numberOfBytes));

            if (bytesRead == 0) { // EOF
                return nil();
            }

            return createString(RopeBuilder.createRopeBuilder(bytes, 0, bytesRead));
        }

    }

    @Primitive(name = "io_reopen")
    public static abstract class IOReopenPrimitiveNode extends IOPrimitiveArrayArgumentsNode {

        @TruffleBoundary(transferToInterpreterOnException = false)
        @Specialization
        protected DynamicObject reopen(DynamicObject self, DynamicObject target) {
            final int fdSelf = Layouts.IO.getDescriptor(self);
            final int fdTarget = Layouts.IO.getDescriptor(target);

            ensureSuccessful(posix().dup2(fdTarget, fdSelf));

            final int newSelfMode = ensureSuccessful(posix().fcntl(fdSelf, Fcntl.F_GETFL));
            Layouts.IO.setMode(self, newSelfMode);
            return self;
        }

    }

    @Primitive(name = "io_reopen_path", lowerFixnum = 2)
    public static abstract class IOReopenPathPrimitiveNode extends IOPrimitiveArrayArgumentsNode {

        @TruffleBoundary(transferToInterpreterOnException = false)
        @Specialization
        protected DynamicObject reopenPath(DynamicObject self, DynamicObject path, int mode) {
            final int fdSelf = Layouts.IO.getDescriptor(self);
            final int newFdSelf;
            final String targetPathString = StringOperations.getString(path);

            int fdTarget = ensureSuccessful(posix().open(targetPathString, mode, 0_666));

            final int result = posix().dup2(fdTarget, fdSelf);
            if (result == -1) {
                final int errno = posix().errno();
                if (errno == Errno.EBADF.intValue()) {
                    Layouts.IO.setDescriptor(self, fdTarget);
                    newFdSelf = fdTarget;
                } else {
                    if (fdTarget > 0) {
                        ensureSuccessful(posix().close(fdTarget));
                    }
                    ensureSuccessful(result, errno, targetPathString); // throws
                    return self;
                }
            } else {
                ensureSuccessful(posix().close(fdTarget));
                newFdSelf = fdSelf;
            }

            final int newSelfMode = ensureSuccessful(posix().fcntl(newFdSelf, Fcntl.F_GETFL));
            Layouts.IO.setMode(self, newSelfMode);
            return self;
        }

    }

    @Primitive(name = "io_write")
    public static abstract class IOWritePrimitiveNode extends IOPrimitiveArrayArgumentsNode {

        @TruffleBoundary(transferToInterpreterOnException = false)
        @Specialization(guards = "isRubyString(string)")
        public int write(DynamicObject file, DynamicObject string) {
            final int fd = Layouts.IO.getDescriptor(file);

            final Rope rope = rope(string);
            final IOWritePrimitiveNode currentNode = this;

            RopeOperations.visitBytes(rope, (bytes, offset, length) -> {
                final ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length);

                while (buffer.hasRemaining()) {
                    int written = getContext().getThreadManager().runBlockingSystemCallUntilResult(currentNode,
                            () -> posix().write(fd, buffer, buffer.remaining()));
                    ensureSuccessful(written);
                    buffer.position(buffer.position() + written);
                }
            });

            return rope.byteLength();
        }

    }

    @Primitive(name = "io_write_nonblock")
    public static abstract class IOWriteNonBlockPrimitiveNode extends IOPrimitiveArrayArgumentsNode {

        static class StopWriting extends ControlFlowException {
            private static final long serialVersionUID = 1096318435617097172L;

            final int bytesWritten;

            public StopWriting(int bytesWritten) {
                this.bytesWritten = bytesWritten;
            }
        }

        @TruffleBoundary(transferToInterpreterOnException = false)
        @Specialization(guards = "isRubyString(string)")
        public int writeNonBlock(DynamicObject io, DynamicObject string) {
            setNonBlocking(io);

            final int fd = Layouts.IO.getDescriptor(io);
            final Rope rope = rope(string);

            final IOWriteNonBlockPrimitiveNode currentNode = this;

            try {
                RopeOperations.visitBytes(rope, new BytesVisitor() {

                    int totalWritten = 0;

                    @Override
                    public void accept(byte[] bytes, int offset, int length) {
                        final ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length);

                        while (buffer.hasRemaining()) {
                            final int result = getContext().getThreadManager().runBlockingSystemCallUntilResult(currentNode,
                                    () -> posix().write(fd, buffer, buffer.remaining()));
                            if (result <= 0) {
                                int errno = posix().errno();
                                if (errno == Errno.EAGAIN.intValue() || errno == Errno.EWOULDBLOCK.intValue()) {
                                    throw new RaiseException(coreExceptions().eAGAINWaitWritable(currentNode));
                                } else {
                                    ensureSuccessful(result);
                                }
                            } else {
                                totalWritten += result;
                            }

                            if (result < buffer.remaining()) {
                                throw new StopWriting(totalWritten);
                            }

                            buffer.position(buffer.position() + result);
                        }
                    }

                });
            } catch (StopWriting e) {
                return e.bytesWritten;
            }

            return rope.byteLength();
        }

        protected void setNonBlocking(DynamicObject io) {
            final int fd = Layouts.IO.getDescriptor(io);
            int flags = ensureSuccessful(posix().fcntl(fd, Fcntl.F_GETFL));

            if ((flags & OpenFlags.O_NONBLOCK.intValue()) == 0) {
                flags |= OpenFlags.O_NONBLOCK.intValue();
                ensureSuccessful(posix().fcntlInt(fd, Fcntl.F_SETFL, flags));
                Layouts.IO.setMode(io, flags);
            }
        }

    }

    @Primitive(name = "io_seek", lowerFixnum = { 1, 2 })
    public static abstract class IOSeekPrimitiveNode extends IOPrimitiveArrayArgumentsNode {

        @Specialization
        public int seek(DynamicObject io, int amount, int whence) {
            final int fd = Layouts.IO.getDescriptor(io);
            return ensureSuccessful(posix().lseek(fd, amount, whence));
        }

    }

    @Primitive(name = "io_accept")
    public abstract static class AcceptNode extends IOPrimitiveArrayArgumentsNode {

        @SuppressWarnings("restriction")
        @TruffleBoundary(transferToInterpreterOnException = false)
        @Specialization
        public int accept(DynamicObject io) {
            final int fd = Layouts.IO.getDescriptor(io);

            final int[] addressLength = { 16 };
            final int newFd;

            try (Pointer address = Pointer.malloc(addressLength[0])) {
                newFd = getContext().getThreadManager().runBlockingSystemCallUntilResult(this,
                        () -> nativeSockets().accept(fd, address, addressLength));
                return ensureSuccessful(newFd);
            }
        }

    }

    @Primitive(name = "io_sysread", lowerFixnum = 1)
    public static abstract class IOSysReadPrimitiveNode extends IOPrimitiveArrayArgumentsNode {

        @TruffleBoundary(transferToInterpreterOnException = false)
        @Specialization
        public DynamicObject sysread(DynamicObject file, int length) {
            final int fd = Layouts.IO.getDescriptor(file);

            final ByteBuffer buffer = ByteBuffer.allocate(length);

            int toRead = length;

            while (toRead > 0) {
                final int finalToRead = toRead;
                final int bytesRead = getContext().getThreadManager().runBlockingSystemCallUntilResult(this,
                        () -> posix().read(fd, buffer, finalToRead));

                ensureSuccessful(bytesRead);

                if (bytesRead == 0) { // EOF
                    if (toRead == length) { // if EOF at first iteration
                        return nil();
                    } else {
                        break;
                    }
                }

                buffer.position(bytesRead);
                toRead -= bytesRead;
            }

            return createString(RopeBuilder.createRopeBuilder(buffer.array(), buffer.arrayOffset(), buffer.position()));
        }

    }

    @Primitive(name = "io_select", needsSelf = false, lowerFixnum = 4)
    public static abstract class IOSelectPrimitiveNode extends IOPrimitiveArrayArgumentsNode {

        @Specialization(guards = "isNil(noTimeout)")
        public Object selectNoTimeout(DynamicObject readables, DynamicObject writables, DynamicObject errorables, DynamicObject noTimeout) {
            Object result;
            do {
                result = doSelect(readables, writables, errorables, Integer.MAX_VALUE);
            } while (result == nil());
            return result;
        }

        @Specialization(guards = { "isNilOrArray(readables)", "isNilOrArray(writables)", "isNilOrArray(errorables)" })
        public Object selectWithTimeout(DynamicObject readables, DynamicObject writables, DynamicObject errorables, int timeoutMicros) {
            return doSelect(readables, writables, errorables, timeoutMicros);
        }

        @TruffleBoundary(transferToInterpreterOnException = false)
        private Object doSelect(DynamicObject readables, DynamicObject writables, DynamicObject errorables, int timeoutMicros) {
            final Object[] readableObjects = toObjectArray(readables);
            final Object[] writableObjects = toObjectArray(writables);
            final Object[] errorableObjects = toObjectArray(errorables);

            final int[] readableFDs = getFileDescriptors(readableObjects);
            final int[] writableFDs = getFileDescriptors(writableObjects);
            final int[] errorableFDs = getFileDescriptors(errorableObjects);

            final int nfds;
            if (readableFDs.length == 0 && writableFDs.length == 0 && errorableFDs.length == 0) {
                nfds = 0;
            } else {
                nfds = max(readableFDs, writableFDs, errorableFDs) + 1;
            }

            try (FDSet readableFDSet = getContext().getNativePlatform().createFDSet();
                    FDSet writableFDSet = getContext().getNativePlatform().createFDSet();
                    FDSet errorableFDSet = getContext().getNativePlatform().createFDSet()) {

                final Timeval timeoutToUse = new DefaultNativeTimeval(jnr.ffi.Runtime.getSystemRuntime());
                timeoutToUse.setTime(new long[]{
                        timeoutMicros / 1_000_000,
                        timeoutMicros % 1_000_000
                });

                final long end = System.nanoTime() + timeoutMicros * 1000L;

                final int result = getContext().getThreadManager().runBlockingSystemCallUntilResult(this, () -> {
                    // Set each fd each time since they are removed if the fd was not available
                    for (int fd : readableFDs) {
                        readableFDSet.set(fd);
                    }
                    for (int fd : writableFDs) {
                        writableFDSet.set(fd);
                    }
                    for (int fd : errorableFDs) {
                        errorableFDSet.set(fd);
                    }

                    final int ret = nativeSockets().select(
                            nfds,
                            readableFDSet.getPointer(),
                            writableFDSet.getPointer(),
                            errorableFDSet.getPointer(),
                            timeoutToUse);

                    if (ret < 0) { // interrupted or error, adjust timeout
                        final long remainingMicros = (end - System.nanoTime()) / 1000L;

                        timeoutToUse.setTime(new long[]{
                                remainingMicros / 1_000_000,
                                remainingMicros % 1_000_000
                        });
                    }

                    return ret;
                });

                ensureSuccessful(result);

                if (result == 0) {
                    return nil();
                }

                return createArray(new Object[]{
                        getSetObjects(readableObjects, readableFDs, readableFDSet),
                        getSetObjects(writableObjects, writableFDs, writableFDSet),
                        getSetObjects(errorableObjects, errorableFDs, errorableFDSet)
                }, 3);
            }
        }

        private int[] getFileDescriptors(Object[] objects) {
            final int[] fileDescriptors = new int[objects.length];

            for (int n = 0; n < objects.length; n++) {
                if (!(objects[n] instanceof DynamicObject)) {
                    throw new UnsupportedOperationException();
                }

                DynamicObject object = (DynamicObject) objects[n];
                if (!RubyGuards.isRubyIO(object) && RubyGuards.isRubyArray(object)) {
                    // A [object with #to_io, IO] pair
                    object = (DynamicObject) ArrayOperations.toObjectArray(object)[1];
                }
                fileDescriptors[n] = Layouts.IO.getDescriptor(object);
            }

            return fileDescriptors;
        }

        private static int max(int[]... arrays) {
            int max = Integer.MIN_VALUE;

            for (int n = 0; n < arrays.length; n++) {
                final int[] array = arrays[n];
                for (int i = 0; i < array.length; i++) {
                    if (array[i] > max) {
                        max = array[i];
                    }
                }
            }

            return max;
        }

        private DynamicObject getSetObjects(Object[] ioObjects, int[] fds, FDSet set) {
            final Object[] setObjects = new Object[ioObjects.length];
            int setFdsCount = 0;

            for (int n = 0; n < ioObjects.length; n++) {
                if (set.isSet(fds[n])) {
                    DynamicObject object = (DynamicObject) ioObjects[n];
                    if (!RubyGuards.isRubyIO(object) && RubyGuards.isRubyArray(object)) {
                        // A [object with #to_io, IO] pair
                        object = (DynamicObject) ArrayOperations.toObjectArray(object)[0];
                    }
                    setObjects[setFdsCount] = object;
                    setFdsCount++;
                }
            }

            return createArray(setObjects, setFdsCount);
        }

        protected boolean isNilOrArray(DynamicObject fds) {
            return isNil(fds) || RubyGuards.isRubyArray(fds);
        }

        private Object[] toObjectArray(DynamicObject nilOrArray) {
            if (nilOrArray == nil()) {
                return ArrayUtils.EMPTY_ARRAY;
            } else {
                return ArrayOperations.toObjectArray(nilOrArray);
            }
        }

    }

    @Primitive(name = "io_get_last_line", needsSelf = false)
    public static abstract class GetLastLineNode extends PrimitiveArrayArgumentsNode {

        @Child ReadCallerFrameNode callerFrameNode = new ReadCallerFrameNode(CallerFrameAccess.READ_WRITE);
        @Child FindThreadAndFrameLocalStorageNode threadLocalNode;

        @Specialization
        public Object getLastLine(VirtualFrame frame) {
            Frame callerFrame = callerFrameNode.execute(frame);
            if (threadLocalNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                threadLocalNode = insert(FindThreadAndFrameLocalStorageNodeGen.create(LAST_LINE_VARIABLE));
            }
            return threadLocalNode.execute(callerFrame.materialize()).get();
        }
    }

    @Primitive(name = "io_set_last_line", needsSelf = false)
    public static abstract class SetLastLineNode extends PrimitiveArrayArgumentsNode {

        @Child ReadCallerFrameNode readCallerFrame = new ReadCallerFrameNode(CallerFrameAccess.READ_WRITE);
        @Child FindThreadAndFrameLocalStorageNode threadLocalNode;

        public static RegexpSetLastMatchPrimitiveNode create() {
            return RegexpSetLastMatchPrimitiveNodeFactory.create(null);
        }

        public abstract DynamicObject executeSetLastMatch(VirtualFrame frame, Object matchData);

        @Specialization
        public DynamicObject setLastLine(VirtualFrame frame, DynamicObject matchData) {
            if (threadLocalNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                threadLocalNode = insert(FindThreadAndFrameLocalStorageNodeGen.create(LAST_LINE_VARIABLE));
            }
            Frame callerFrame = readCallerFrame.execute(frame);
            ThreadAndFrameLocalStorage lastMatch = threadLocalNode.execute(callerFrame.materialize());
            lastMatch.set(matchData);
            return matchData;
        }
    }

}
