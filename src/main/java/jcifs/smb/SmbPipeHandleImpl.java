/*
 * © 2017 AgNO3 Gmbh & Co. KG
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package jcifs.smb;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jcifs.CIFSException;
import jcifs.SmbConstants;
import jcifs.SmbPipeHandle;
import jcifs.SmbPipeResource;
import jcifs.internal.smb1.trans.TransWaitNamedPipe;
import jcifs.internal.smb1.trans.TransWaitNamedPipeResponse;


/**
 * @author mbechler
 *
 */
class SmbPipeHandleImpl implements SmbPipeHandleInternal {

    private static final Logger log = LoggerFactory.getLogger(SmbPipeHandleImpl.class);

    private final SmbNamedPipe pipe;
    private final boolean transact;
    private final boolean call;

    private final int openFlags;
    private final int access;
    private volatile boolean open = true;

    private SmbFileHandleImpl handle;
    private SmbPipeOutputStream output;
    private SmbPipeInputStream input;

    private final String uncPath;

    private SmbTreeHandleImpl treeHandle;

    private int sharing = SmbConstants.DEFAULT_SHARING;


    /**
     * @param pipe
     */
    public SmbPipeHandleImpl ( SmbNamedPipe pipe ) {
        this.pipe = pipe;
        this.transact = ( pipe.getPipeType() & SmbPipeResource.PIPE_TYPE_TRANSACT ) == SmbPipeResource.PIPE_TYPE_TRANSACT;
        this.call = ( pipe.getPipeType() & SmbPipeResource.PIPE_TYPE_CALL ) == SmbPipeResource.PIPE_TYPE_CALL;
        this.openFlags = ( pipe.getPipeType() & 0xFFFF00FF ) | SmbConstants.O_EXCL;
        this.access = ( pipe.getPipeType() >>> 16 ) & 0xFFFF | SmbConstants.FILE_WRITE_DATA | 0x20000;
        this.uncPath = this.pipe.getUncPath();
    }


    /**
     * {@inheritDoc}
     *
     * @see jcifs.SmbPipeHandle#unwrap(java.lang.Class)
     */
    @SuppressWarnings ( "unchecked" )
    @Override
    public <T extends SmbPipeHandle> T unwrap ( Class<T> type ) {
        if ( type.isAssignableFrom(this.getClass()) ) {
            return (T) this;
        }
        throw new ClassCastException();
    }


    /**
     * {@inheritDoc}
     *
     * @see jcifs.SmbPipeHandle#getPipe()
     */
    @Override
    public SmbNamedPipe getPipe () {
        return this.pipe;
    }


    SmbTreeHandleImpl ensureTreeConnected () throws SmbException {
        if ( this.treeHandle == null ) {
            // extra acquire to keep the tree alive
            this.treeHandle = this.pipe.ensureTreeConnected();
        }
        return this.treeHandle.acquire();
    }


    public String getUncPath () {
        return this.uncPath;
    }


    /**
     * {@inheritDoc}
     *
     * @see jcifs.SmbPipeHandle#isOpen()
     */
    @Override
    public boolean isOpen () {
        return this.open && this.handle != null && this.handle.isValid();
    }


    /**
     * 
     * {@inheritDoc}
     *
     * @see jcifs.smb.SmbPipeHandleInternal#getSessionKey()
     */
    @Override
    public byte[] getSessionKey () throws CIFSException {
        try ( SmbTreeHandleImpl th = ensureTreeConnected();
              SmbSessionImpl sess = th.getSession() ) {
            return sess.getSessionKey();
        }
    }


    /**
     * {@inheritDoc}
     *
     * @see jcifs.SmbPipeHandle#isStale()
     */
    @Override
    public boolean isStale () {
        return !this.open || ( this.handle != null && !this.handle.isValid() );
    }


    synchronized SmbFileHandleImpl ensureOpen () throws CIFSException {
        if ( !this.open ) {
            throw new SmbException("Pipe handle already closed");
        }

        if ( !isOpen() ) {
            try ( SmbTreeHandleImpl th = ensureTreeConnected() ) {
                // TODO: wait for pipe, still not sure when this needs to be called exactly
                if ( this.uncPath.startsWith("\\pipe\\") ) {
                    th.send(new TransWaitNamedPipe(th.getConfig(), this.uncPath), new TransWaitNamedPipeResponse(th.getConfig()));
                }

                if ( th.hasCapability(SmbConstants.CAP_NT_SMBS) || this.uncPath.startsWith("\\pipe\\") ) {
                    this.handle = this.pipe.openUnshared(this.openFlags, this.access, this.sharing, SmbConstants.ATTR_NORMAL, 0);
                }
                else {
                    // at least on samba, SmbComOpenAndX fails without the pipe prefix
                    this.handle = this.pipe
                            .openUnshared("\\pipe" + getUncPath(), this.openFlags, this.access, this.sharing, SmbConstants.ATTR_NORMAL, 0);
                }
                // one extra acquire to keep this open till the stream is released
                return this.handle.acquire();
            }

        }
        log.trace("Pipe already open");
        return this.handle.acquire();
    }


    /**
     * {@inheritDoc}
     *
     * @see jcifs.SmbPipeHandle#getInput()
     */
    @Override
    public SmbPipeInputStream getInput () throws CIFSException {

        if ( !this.open ) {
            throw new SmbException("Already closed");
        }

        if ( this.input != null ) {
            return this.input;
        }

        try ( SmbTreeHandleImpl th = ensureTreeConnected() ) {
            if ( this.transact ) {
                this.input = new TransactNamedPipeInputStream(this, th);
            }
            else if ( this.call ) {
                this.input = new TransactNamedPipeInputStream(this, th);
            }
            else {
                this.input = new SmbPipeInputStream(this, th);
            }
        }
        return this.input;
    }


    /**
     * {@inheritDoc}
     *
     * @see jcifs.SmbPipeHandle#getOutput()
     */
    @Override
    public SmbPipeOutputStream getOutput () throws CIFSException {
        if ( !this.open ) {
            throw new SmbException("Already closed");
        }

        if ( this.output != null ) {
            return this.output;
        }

        try ( SmbTreeHandleImpl th = ensureTreeConnected() ) {
            if ( this.transact ) {
                this.output = new TransactNamedPipeOutputStream(this, th);
            }
            else if ( this.call ) {
                this.output = new TransactCallNamedPipeOutputStream(this, th);
            }
            else {
                this.output = new SmbPipeOutputStream(this, th);
            }
        }
        return this.output;
    }


    /**
     * 
     * {@inheritDoc}
     *
     * @see jcifs.smb.SmbPipeHandleInternal#getPipeType()
     */
    @Override
    public int getPipeType () {
        return this.pipe.getPipeType();
    }


    /**
     * {@inheritDoc}
     *
     * @see jcifs.SmbPipeHandle#close()
     */
    @Override
    public synchronized void close () throws CIFSException {
        boolean wasOpen = isOpen();
        this.open = false;
        if ( this.input != null ) {
            this.input.close();
            this.input = null;
        }

        if ( this.output != null ) {
            this.output.close();
            this.output = null;
        }

        try {
            if ( wasOpen ) {
                this.handle.close();
            }
            this.handle = null;
        }
        finally {
            if ( this.treeHandle != null ) {
                this.treeHandle.release();
            }
        }
    }

}