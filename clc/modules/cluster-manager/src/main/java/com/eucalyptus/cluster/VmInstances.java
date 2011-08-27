/*******************************************************************************
 *Copyright (c) 2009  Eucalyptus Systems, Inc.
 * 
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, only version 3 of the License.
 * 
 * 
 *  This file is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  for more details.
 * 
 *  You should have received a copy of the GNU General Public License along
 *  with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 *  Please contact Eucalyptus Systems, Inc., 130 Castilian
 *  Dr., Goleta, CA 93101 USA or visit <http://www.eucalyptus.com/licenses/>
 *  if you need additional information or have any questions.
 * 
 *  This file may incorporate work covered under the following copyright and
 *  permission notice:
 * 
 *    Software License Agreement (BSD License)
 * 
 *    Copyright (c) 2008, Regents of the University of California
 *    All rights reserved.
 * 
 *    Redistribution and use of this software in source and binary forms, with
 *    or without modification, are permitted provided that the following
 *    conditions are met:
 * 
 *      Redistributions of source code must retain the above copyright notice,
 *      this list of conditions and the following disclaimer.
 * 
 *      Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in the
 *      documentation and/or other materials provided with the distribution.
 * 
 *    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 *    IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 *    TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 *    PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 *    OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 *    EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 *    PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 *    PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 *    LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 *    NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *    SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. USERS OF
 *    THIS SOFTWARE ACKNOWLEDGE THE POSSIBLE PRESENCE OF OTHER OPEN SOURCE
 *    LICENSED MATERIAL, COPYRIGHTED MATERIAL OR PATENTED MATERIAL IN THIS
 *    SOFTWARE, AND IF ANY SUCH MATERIAL IS DISCOVERED THE PARTY DISCOVERING
 *    IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF CALIFORNIA, SANTA
 *    BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE REMEDY, WHICH IN
 *    THE REGENTS' DISCRETION MAY INCLUDE, WITHOUT LIMITATION, REPLACEMENT
 *    OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO IDENTIFIED, OR
 *    WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT NEEDED TO COMPLY WITH
 *    ANY SUCH LICENSES OR RIGHTS.
 *******************************************************************************/
/*
 *
 * @author chris grzegorczyk <grze@eucalyptus.com>
 */

package com.eucalyptus.cluster;

import java.security.MessageDigest;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.zip.Adler32;
import javax.persistence.EntityTransaction;
import org.apache.log4j.Logger;
import org.hibernate.criterion.Example;
import com.eucalyptus.address.Address;
import com.eucalyptus.address.Addresses;
import com.eucalyptus.auth.Accounts;
import com.eucalyptus.auth.AuthException;
import com.eucalyptus.auth.Permissions;
import com.eucalyptus.auth.policy.PolicySpec;
import com.eucalyptus.auth.principal.Account;
import com.eucalyptus.cloud.CloudMetadata.VmInstanceMetadata;
import com.eucalyptus.cluster.VmInstance.VmState;
import com.eucalyptus.cluster.callback.TerminateCallback;
import com.eucalyptus.component.Dispatcher;
import com.eucalyptus.component.Partitions;
import com.eucalyptus.component.ServiceConfiguration;
import com.eucalyptus.component.id.Storage;
import com.eucalyptus.configurable.ConfigurableClass;
import com.eucalyptus.configurable.ConfigurableField;
import com.eucalyptus.configurable.ConfigurableProperty;
import com.eucalyptus.configurable.ConfigurablePropertyException;
import com.eucalyptus.configurable.PropertyChangeListener;
import com.eucalyptus.context.Context;
import com.eucalyptus.context.Contexts;
import com.eucalyptus.crypto.Digest;
import com.eucalyptus.entities.Entities;
import com.eucalyptus.entities.TransactionException;
import com.eucalyptus.network.NetworkGroups;
import com.eucalyptus.records.EventRecord;
import com.eucalyptus.records.EventType;
import com.eucalyptus.records.Logs;
import com.eucalyptus.system.Threads;
import com.eucalyptus.util.EucalyptusCloudException;
import com.eucalyptus.util.OwnerFullName;
import com.eucalyptus.util.RestrictedTypes.QuantityMetricFunction;
import com.eucalyptus.util.async.AsyncRequests;
import com.eucalyptus.util.async.Request;
import com.eucalyptus.util.async.UnconditionalCallback;
import com.eucalyptus.ws.client.ServiceDispatcher;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import edu.ucsb.eucalyptus.msgs.AttachedVolume;
import edu.ucsb.eucalyptus.msgs.BaseMessage;
import edu.ucsb.eucalyptus.msgs.DetachStorageVolumeType;
import edu.ucsb.eucalyptus.msgs.TerminateInstancesResponseType;
import edu.ucsb.eucalyptus.msgs.TerminateInstancesType;

@ConfigurableClass( root = "vmstate", description = "Parameters controlling the lifecycle of virtual machines." )
public class VmInstances {
  @ConfigurableField( description = "Amount of time (in milliseconds) before a VM which is not reported by a cluster will be marked as terminated.", initial = "" + 10 * 60 * 1000 )
  public static Long         SHUT_DOWN_TIME                = 10 * 60 * 1000l;
  @ConfigurableField( description = "Amount of time (in milliseconds) that a terminated VM will continue to be reported.", initial = "" + 60 * 60 * 1000 )
  public static Long         BURY_TIME                     = 60 * 60 * 1000l;
  @ConfigurableField( description = "Maximum amount of time (in seconds) that the network topology service takes to propagate state changes.", initial = "" + 60 * 60 * 1000 )
  public static Long         NETWORK_METADATA_REFRESH_TIME = 15l;
  @ConfigurableField( description = "Prefix to use for instance MAC addresses.", initial = "d0:0d" )
  public static String       MAC_PREFIX                    = "d0:0d";
  @ConfigurableField( description = "Subdomain to use for instance DNS.", initial = ".eucalyptus", changeListener = SubdomainListener.class )
  public static final String INSTANCE_SUBDOMAIN            = ".eucalyptus";
  
  public static class SubdomainListener implements PropertyChangeListener {
    @Override
    public void fireChange( ConfigurableProperty t, Object newValue ) throws ConfigurablePropertyException {
      
      if ( !newValue.toString( ).startsWith( "." ) || newValue.toString( ).endsWith( "." ) )
        throw new ConfigurablePropertyException( "Subdomain must begin and cannot end with a '.' -- e.g., '." + newValue.toString( ).replaceAll( "\\.$", "" )
                                                 + "' is correct." + t.getFieldName( ) );
      
    }
  }
  
  private static Logger LOG = Logger.getLogger( VmInstances.class );
  
  enum VmIsOperational implements Predicate<VmInstance> {
    INSTANCE;
    
    @Override
    public boolean apply( VmInstance vm ) {
      return VmState.PENDING.equals( vm.getState( ) ) || VmState.RUNNING.equals( vm.getState( ) );
    }
    
  }
  
  @QuantityMetricFunction( VmInstanceMetadata.class )
  public enum CountVmInstances implements Function<OwnerFullName, Long> {
    INSTANCE;
    
    @Override
    public Long apply( final OwnerFullName input ) {
      final EntityTransaction db = Entities.get( VmInstance.class );
      final int i;
      try {
        i = Entities.createCriteria( VmInstance.class )
                    .add( Example.create( VmInstance.named( input, null ) ) )
                    .setReadOnly( true )
                    .setCacheable( false )
                    .list( )
                    .size( );
      } finally {
        db.rollback( );
      }
      return ( long ) i;
    }
  }
  
  public static String getId( final Long rsvId, final int launchIndex ) {
    String vmId = null;
    do {
      final MessageDigest digest = Digest.MD5.get( );
      digest.reset( );
      digest.update( Long.toString( rsvId + launchIndex + System.currentTimeMillis( ) ).getBytes( ) );
      
      final Adler32 hash = new Adler32( );
      hash.reset( );
      hash.update( digest.digest( ) );
      vmId = String.format( "i-%08X", hash.getValue( ) );
    } while ( VmInstances.contains( vmId ) );
    return vmId;
  }
  
  public static Predicate<VmInstance> withPrivateAddress( final String ip ) {
    return new Predicate<VmInstance>( ) {
      @Override
      public boolean apply( VmInstance vm ) {
        return ip.equals( vm.getPrivateAddress( ) ) && VmIsOperational.INSTANCE.apply( vm );
      }
    };
  }
  
  public static VmInstance lookupByInstanceIp( final String ip ) throws NoSuchElementException {
    return Iterables.find( listValues( ), vmWithPublicAddress( ip ) );
  }
  
  public static Predicate<VmInstance> vmWithPublicAddress( final String ip ) {
    return new Predicate<VmInstance>( ) {
      @Override
      public boolean apply( VmInstance vm ) {
        return ip.equals( vm.getPublicAddress( ) ) && VmIsOperational.INSTANCE.apply( vm );
      }
    };
  }
  
  public static VmInstance lookupByPublicIp( final String ip ) throws NoSuchElementException {
    return Iterables.find( listValues( ), vmWithPublicAddress( ip ) );
  }
  
  public static Predicate<VmInstance> withBundleId( final String bundleId ) {
    return new Predicate<VmInstance>( ) {
      @Override
      public boolean apply( VmInstance vm ) {
        return vm.getBundleTask( ) != null && bundleId.equals( vm.getBundleTask( ).getBundleId( ) );
      }
    };
  }
  
  public static VmInstance lookupByBundleId( final String bundleId ) throws NoSuchElementException {
    return Iterables.find( listValues( ), withBundleId( bundleId ) );
  }
  
  public static UnconditionalCallback getCleanUpCallback( final Address address, final VmInstance vm, final Cluster cluster ) {
    final UnconditionalCallback cleanup = new UnconditionalCallback( ) {
      @Override
      public void fire( ) {
        if ( address != null ) {
          try {
            if ( address.isSystemOwned( ) ) {
              EventRecord.caller( SystemState.class, EventType.VM_TERMINATING, "SYSTEM_ADDRESS", address.toString( ) ).debug( );
              Addresses.release( address );
            } else {
              EventRecord.caller( SystemState.class, EventType.VM_TERMINATING, "USER_ADDRESS", address.toString( ) ).debug( );
              AsyncRequests.newRequest( address.unassign( ).getCallback( ) ).dispatch( address.getPartition( ) );
            }
          } catch ( final IllegalStateException e ) {} catch ( final Throwable e ) {
            LOG.debug( e, e );
          }
        }
      }
    };
    return cleanup;
  }
  
  public static void cleanUp( final VmInstance vm ) {
    LOG.trace( Logs.dump( vm ) );
    LOG.trace( Threads.currentStackString( ) );
    try {
      final Cluster cluster = Clusters.getInstance( ).lookup( vm.lookupPartition( ) );
      VmInstances.cleanUpAttachedVolumes( vm );
      
      Address address = null;
      final Request<TerminateInstancesType, TerminateInstancesResponseType> req = AsyncRequests.newRequest( new TerminateCallback( vm.getInstanceId( ) ) );
      if ( NetworkGroups.networkingConfiguration( ).hasNetworking( ) ) {
        try {
          address = Addresses.getInstance( ).lookup( vm.getPublicAddress( ) );
        } catch ( final NoSuchElementException e ) {} catch ( final Throwable e1 ) {
          LOG.debug( e1, e1 );
        }
      }
      req.then( VmInstances.getCleanUpCallback( address, vm, cluster ) );
      req.dispatch( cluster.getConfiguration( ) );
    } catch ( final Throwable e ) {
      LOG.error( e, e );
    }
  }
  
  private static final Predicate<AttachedVolume> anyVolumePred = new Predicate<AttachedVolume>( ) {
                                                                 @Override
                                                                 public boolean apply( final AttachedVolume arg0 ) {
                                                                   return true;
                                                                 }
                                                               };
  
  private static void cleanUpAttachedVolumes( final VmInstance vm ) {
    try {
      final Cluster cluster = Clusters.getInstance( ).lookup( vm.lookupPartition( ) );
      vm.eachVolumeAttachment( new Predicate<AttachedVolume>( ) {
        @Override
        public boolean apply( final AttachedVolume arg0 ) {
          try {
            final ServiceConfiguration sc = Partitions.lookupService( Storage.class, vm.lookupPartition( ) );
            vm.removeVolumeAttachment( arg0.getVolumeId( ) );
            final Dispatcher scDispatcher = ServiceDispatcher.lookup( sc );
            scDispatcher.send( new DetachStorageVolumeType( cluster.getNode( vm.getServiceTag( ) ).getIqn( ), arg0.getVolumeId( ) ) );
            return true;
          } catch ( final Throwable e ) {
            LOG.error( "Failed sending Detach Storage Volume for: " + arg0.getVolumeId( )
                       + ".  Will keep trying as long as instance is reported.  The request failed because of: " + e.getMessage( ), e );
            return false;
          }
        }
      } );
    } catch ( final Exception ex ) {
      LOG.error( "Failed to lookup Storage Controller configuration for: " + vm.getInstanceId( ) + " (placement=" + vm.getPartition( ) + ").  " );
    }
  }
  
  public static VmInstance restrictedLookup( final BaseMessage request, final String instanceId ) throws EucalyptusCloudException {
    final VmInstance vm = VmInstances.lookup( instanceId ); //TODO: test should throw error.
    final Context ctx = Contexts.lookup( );
    Account addrAccount = null;
    try {
      addrAccount = Accounts.lookupUserById( vm.getOwner( ).getUniqueId( ) ).getAccount( );
    } catch ( final AuthException e ) {
      throw new EucalyptusCloudException( e );
    }
    if ( !Permissions.isAuthorized( PolicySpec.VENDOR_EC2, PolicySpec.EC2_RESOURCE_INSTANCE, instanceId, addrAccount, PolicySpec.requestToAction( request ),
                                    ctx.getUser( ) ) ) {
      throw new EucalyptusCloudException( "Permission denied while trying to access instance " + instanceId + " by " + ctx.getUser( ) );
    }
    return vm;
  }
  
  public static String asMacAddress( final String instanceId ) {
    return String.format( "%s:%s:%s:%s:%s",
                          VmInstances.MAC_PREFIX,
                          instanceId.substring( 2, 4 ),
                          instanceId.substring( 4, 6 ),
                          instanceId.substring( 6, 8 ),
                          instanceId.substring( 8, 10 ) );
  }
  
  @Deprecated
  public static VmInstance lookup( final String name ) throws NoSuchElementException {
    return VmInstance.Lookup.INSTANCE.apply( name );
  }
  
  @Deprecated
  public static VmInstance register( final VmInstance obj ) {
    return VmInstance.Transitions.REGISTER.apply( obj );
  }
  
  @Deprecated
  public static VmInstance deregister( final VmInstance vm ) throws TransactionException {
    return VmInstance.Transitions.TERMINATE.apply( vm );
  }
  
  @Deprecated
  public static VmInstance deregister( final String key ) throws NoSuchElementException {
    return Functions.compose( VmInstance.Transitions.TERMINATE, VmInstance.Lookup.INSTANCE ).apply( key );
  }
  
  @Deprecated
  public static List<VmInstance> listValues( ) {
    final EntityTransaction db = Entities.get( VmInstance.class );
    try {
      final List<VmInstance> vms = Entities.query( VmInstance.named( null, null ) );
      db.commit( );
      return Lists.newArrayList( vms );
    } catch ( final Exception ex ) {
      Logs.extreme( ).error( ex, ex );
      db.rollback( );
      return Lists.newArrayList( );
    }
  }
  
  public static boolean contains( final String name ) {
    final EntityTransaction db = Entities.get( VmInstance.class );
    try {
      final VmInstance vm = Entities.uniqueResult( VmInstance.named( null, name ) );
      db.commit( );
      return true;
    } catch ( RuntimeException ex ) {
      db.rollback( );
      return false;
    } catch ( TransactionException ex ) {
      db.rollback( );
      return false;
    }
  }
  
}
