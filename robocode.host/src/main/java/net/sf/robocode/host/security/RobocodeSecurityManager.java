/*******************************************************************************
 * Copyright (c) 2001, 2008 Mathew A. Nelson and Robocode contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://robocode.sourceforge.net/license/cpl-v10.html
 *
 * Contributors:
 *     Mathew A. Nelson
 *     - Initial API and implementation
 *     Flemming N. Larsen
 *     - Code cleanup
 *     - Added checkPackageAccess() to limit access to the robocode.util Robocode
 *       package only
 *     - Ported to Java 5.0
 *     - Removed unnecessary method synchronization
 *     - Fixed potential NullPointerException in getFileOutputStream()
 *     - Added setStatus()
 *     - Fixed synchronization issue with accessing battleThread
 *     Robert D. Maupin
 *     - Replaced old collection types like Vector and Hashtable with
 *       synchronized List and HashMap
 *     Pavel Savara
 *     - Re-work of robot interfaces
 *     - we create safe AWT queue for robot's thread group
 *******************************************************************************/
package net.sf.robocode.host.security;


import net.sf.robocode.host.IHostedThread;
import net.sf.robocode.host.IThreadManager;
import net.sf.robocode.host.io.RobotFileSystemManager;
import net.sf.robocode.host.io.RobotOutputStream;
import net.sf.robocode.host.serialization.RobocodeObjectInputStream;
import net.sf.robocode.peer.BulletCommand;
import net.sf.robocode.peer.DebugProperty;
import net.sf.robocode.peer.ExecResults;
import net.sf.robocode.peer.TeamMessage;
import net.sf.robocode.serialization.RbSerializer;
import robocode.control.snapshot.BulletState;
import robocode.exception.RobotException;

import java.awt.*;
import java.io.FilePermission;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.AccessControlException;
import java.security.Permission;
import java.util.HashSet;
import java.util.PropertyPermission;
import java.util.Set;
import java.util.ArrayList;


/**
 * @author Mathew A. Nelson (original)
 * @author Flemming N. Larsen (contributor)
 * @author Robert D. Maupin (contributor)
 * @author Pavel Savara (contributor)
 */
public class RobocodeSecurityManager extends SecurityManager {
	private final PrintStream syserr = System.err;

	private final IThreadManager threadManager;
	private final Object safeSecurityContext;
	private final boolean enabled;
	private final boolean experimental;

	private final Set<String> alowedPackages = new HashSet<String>();

	public RobocodeSecurityManager(IThreadManager threadManager, boolean enabled, boolean experimental) {
		super();
		this.threadManager = threadManager;
		this.enabled = enabled;
		this.experimental = experimental;
		safeSecurityContext = getSecurityContext();

		// Loading of classes to prevent security issues on untrusted threads 
		try {
			final ClassLoader scl = ClassLoader.getSystemClassLoader();

			scl.loadClass(BulletState.class.getName());
			scl.loadClass(BulletCommand.class.getName());
			scl.loadClass(ExecResults.class.getName());
			scl.loadClass(TeamMessage.class.getName());
			scl.loadClass(DebugProperty.class.getName());
			scl.loadClass(RobotException.class.getName());
			scl.loadClass(RobocodeObjectInputStream.class.getName());
			scl.loadClass(RbSerializer.class.getName()).newInstance();
			// TODO ? 
			// TODO ? scl.loadClass(RobotPeer.ExecutePrivilegedAction.class.getName());
			scl.loadClass(RobotFileSystemManager.class.getName());
			scl.loadClass(IHostedThread.class.getName());
			scl.loadClass(RobotOutputStream.class.getName());
			scl.loadClass(ThreadGroup.class.getName());
			scl.loadClass(ArrayList.class.getName());
			scl.loadClass(List.class.getName());

			Toolkit.getDefaultToolkit();
		} catch (ClassNotFoundException e) {
			throw new Error("We can't load important classes", e);
		} catch (IllegalAccessException e) {
			throw new Error("We can't load important classes", e);
		} catch (InstantiationException e) {
			throw new Error("We can't load important classes", e);
		}

		alowedPackages.add("util");
		alowedPackages.add("robotinterfaces");
		alowedPackages.add("robotpaint");
		// alowedPackages.add("robocodeGL");
		if (experimental) {
			alowedPackages.add("robotinterfaces.peer");
		}

		ThreadGroup tg = Thread.currentThread().getThreadGroup();

		while (tg != null) {
			threadManager.addSafeThreadGroup(tg);
			tg = tg.getParent();
		}
		// we need to excersize it, to load all used classes on this thread.
		isSafeThread();
		isSafeContext();
	}

	@Override
	public void checkAccess(Thread t) {
		if (!enabled) {
			return;
		}
		Thread c = Thread.currentThread();

		if (isSafeThread(c)) {
			return;
		}
		if (isSafeContext()) {
			return;
		}
		super.checkAccess(t);

		IHostedThread robotProxy = threadManager.getRobotProxy(c);

		if (robotProxy == null) {
			robotProxy = threadManager.getLoadingRobotProxy(c);
			if (robotProxy != null) {
				throw new AccessControlException(
						"Preventing " + robotProxy.getStatics().getName() + " from access to thread: " + t.getName());
			}
			checkPermission(new RuntimePermission("modifyThread"));
			return;
		}

		ThreadGroup cg = c.getThreadGroup();
		ThreadGroup tg = t.getThreadGroup();

		if (cg == null || tg == null) {
			throw new AccessControlException(
					"Preventing " + Thread.currentThread().getName()
					+ " from access to a thread, because threadgroup is null.");
		}

		if (cg != tg) {
			throw new AccessControlException(
					"Preventing " + Thread.currentThread().getName()
					+ " from access to a thread, because threadgroup is different.");
		}

		if (cg.equals(tg)) {
			return;
		}

		throw new AccessControlException(
				"Preventing " + Thread.currentThread().getName() + " from access to threadgroup: " + tg.getName()
				+ ".  You must use your own ThreadGroup.");
	}

	@Override
	public void checkAccess(ThreadGroup g) {
		if (!enabled) {
			return;
		}
		Thread c = Thread.currentThread();

		if (isSafeThread(c)) {
			return;
		}
		if (isSafeContext()) {
			return;
		}
		super.checkAccess(g);

		ThreadGroup cg = c.getThreadGroup();

		if (cg == null) {
			// What the heck is going on here?  JDK 1.3 is sending me a dead thread.
			// This crashes the entire jvm if I don't return here.
			return;
		}

		IHostedThread robotProxy = threadManager.getRobotProxy(c);

		if (robotProxy == null) {
			robotProxy = threadManager.getLoadingRobotProxy(c);
			if (robotProxy != null) {
				throw new AccessControlException(
						"Preventing " + robotProxy.getStatics().getName() + " from access to threadgroup: " + g.getName());
			}
			checkPermission(new RuntimePermission("modifyThreadGroup"));
			return;
		}

		if (g == null) {
			throw new NullPointerException("Thread group can't be null");
		}

		if (cg.equals(g)) {
			if (g.activeCount() > 5) {
				throw new AccessControlException(
						"Preventing " + Thread.currentThread().getName() + " from access to threadgroup: " + g.getName()
						+ ".  You may only create 5 threads.");
			}
			return;
		}

		robotProxy.drainEnergy();
		throw new AccessControlException(
				"Preventing " + Thread.currentThread().getName() + " from access to threadgroup: " + g.getName()
				+ " -- you must use your own ThreadGroup.");

	}

	/**
	 * Robocode's main security:  checkPermission
	 * If the calling thread is in our list of safe threads, allow permission.
	 * Else deny, with a few exceptions.
	 */
	@Override
	public void checkPermission(Permission perm, Object context) {
		if (!enabled) {
			return;
		}
		syserr.println("Checking permission " + perm + " for context " + context);
		checkPermission(perm);
	}

	@Override
	public void checkPermission(Permission perm) {
		// For John Burkey at Apple
		if (!enabled) {
			return;
		}

		// Check if the current running thread is a safe thread
		if (isSafeThread()) {
			return;
		}

		// First, if we're running in Robocode's security context,
		// OR the thread is a safe thread, permission granted.
		// Essentially this optimizes the security manager for Robocode.
		if (isSafeContext()) {
			return;
		}

		// Ok, could be system, could be robot
		// We'll check the system policy (RobocodeSecurityPolicy)
		// This allows doPrivileged blocks to work.
		try {
			super.checkPermission(perm);
		} catch (SecurityException e) {
			// ok wa have a problem
			handleSecurityProblem(perm);
		}
	}

	private void handleSecurityProblem(Permission perm) {
		// For development purposes, allow read any file if override is set.
		if (perm instanceof FilePermission) {
			FilePermission fp = (FilePermission) perm;

			if (fp.getActions().equals("read")) {
				if (System.getProperty("OVERRIDEFILEREADSECURITY", "false").equals("true")) {
					return;
				}
			}
		}

		// Allow reading of properties.
		if (perm instanceof PropertyPermission) {
			if (perm.getActions().equals("read")) {
				return;
			}
		}

		if (perm instanceof RuntimePermission) {
			if (perm.getName() != null && perm.getName().length() >= 24) {
				if (perm.getName().substring(0, 24).equals("accessClassInPackage.sun")) {
					return;
				}
			}
		}

		// Ok, we need to figure out who our robot is.
		Thread c = Thread.currentThread();

		IHostedThread robotProxy = threadManager.getLoadedOrLoadingRobotProxy(c);

		// We don't know who this is, so deny permission.
		if (robotProxy == null) {
			if (perm instanceof RobocodePermission) {
				if (perm.getName().equals("System.out") || perm.getName().equals("System.err")
						|| perm.getName().equals("System.in")) {
					return;
				}
			}

			// Show warning on console.
			syserr.println("Preventing unknown thread " + Thread.currentThread().getName() + " from access: " + perm);
			syserr.flush();

			// Attempt to stop the window from displaying
			// This is a hack.
			if (perm instanceof java.awt.AWTPermission) {
				if (perm.getName().equals("showWindowWithoutWarningBanner")) {
					throw new ThreadDeath();
				}
			}

			// Throw the exception
			throw new AccessControlException(
					"Preventing unknown thread " + Thread.currentThread().getName() + " from access: " + perm);
		}

		// At this point, we have robotProxy set to the RobotProxy object requesting permission.

		// FilePermission access request.
		if (perm instanceof FilePermission) {
			FilePermission fp = (FilePermission) perm;

			// Robot wants access to read something
			if (fp.getActions().equals("read")) {
				// Get the fileSystemManager
				RobotFileSystemManager fileSystemManager = robotProxy.getRobotFileSystemManager();

				// If there is no readable directory, deny access.
				if (fileSystemManager.getReadableDirectory() == null) {
					robotProxy.drainEnergy();
					throw new AccessControlException(
							"Preventing " + robotProxy.getStatics().getName() + " from access: " + perm
							+ ": Robots that are not in a package may not read any files.");
				}
				// If this is a readable file, return.
				if (fileSystemManager.isReadable(fp.getName())) {
					return;
				} // Else disable robot
				robotProxy.drainEnergy();
				throw new AccessControlException(
						"Preventing " + robotProxy.getStatics().getName() + " from access: " + perm
						+ ": You may only read files in your own root package directory. ");
			} // Robot wants access to write something
			else if (fp.getActions().equals("write")) {
				// There isn't one.  Deny access.
				if (!threadManager.checkRobotFileStream()) {
					robotProxy.drainEnergy();
					throw new AccessControlException(
							"Preventing " + robotProxy.getStatics().getName() + " from access: " + perm
							+ ": You must use a RobocodeOutputStream.");
				}

				// Get the fileSystemManager
				RobotFileSystemManager fileSystemManager = robotProxy.getRobotFileSystemManager();

				// If there is no writable directory, deny access
				if (fileSystemManager.getWritableDirectory() == null) {
					robotProxy.drainEnergy();

					throw new AccessControlException(
							"Preventing " + robotProxy.getStatics().getName() + " from access: " + perm
							+ ": Robots that are not in a package may not write any files.");
				}
				// If this is a writable file, permit access
				if (fileSystemManager.isWritable(fp.getName())) {
					return;
				} // else it's not writable, deny access.

				// We are creating the directory.
				if (fileSystemManager.getWritableDirectory().toString().equals(fp.getName())) {
					return;
				} // Not a writable directory.

				robotProxy.drainEnergy();
				// robotProxy.getOut().println("I would allow access to: " + fileSystemManager.getWritableDirectory());
				robotProxy.getOut().println(
						"Preventing " + robotProxy.getStatics().getName() + " from access: " + perm
						+ ": You may only write files in your own data directory. ");

				throw new AccessControlException(
						"Preventing " + robotProxy.getStatics().getName() + " from access: " + perm
						+ ": You may only write files in your own data directory. ");
			} // Robot wants access to write something
			else if (fp.getActions().equals("delete")) {
				// Get the fileSystemManager
				RobotFileSystemManager fileSystemManager = robotProxy.getRobotFileSystemManager();

				// If there is no writable directory, deny access
				if (fileSystemManager.getWritableDirectory() == null) {
					robotProxy.drainEnergy();
					throw new AccessControlException(
							"Preventing " + robotProxy.getStatics().getName() + " from access: " + perm
							+ ": Robots that are not in a package may not delete any files.");
				}
				// If this is a writable file, permit access
				if (fileSystemManager.isWritable(fp.getName())) {
					return;
				} // else it's not writable, deny access.

				// We are deleting our data directory.
				if (fileSystemManager.getWritableDirectory().toString().equals(fp.getName())) {
					// robotProxy.out.println("SYSTEM:  Please let me know if you see this string.  Thanks.  -Mat");
					return;
				} // Not a writable directory.

				robotProxy.drainEnergy();
				throw new AccessControlException(
						"Preventing " + robotProxy.getStatics().getName() + " from access: " + perm
						+ ": You may only delete files in your own data directory. ");
			}
		}

		if (perm instanceof RobocodePermission) {

			if (perm.getName().equals("System.out") || perm.getName().equals("System.err")) {
				robotProxy.println("SYSTEM:  You cannot write to System.out or System.err.");
				robotProxy.println("SYSTEM:  Please use out.println instead of System.out.println");
				throw new AccessControlException(
						"Preventing " + robotProxy.getStatics().getName() + " from access: " + perm);
			} else if (perm.getName().equals("System.in")) {
				robotProxy.println("SYSTEM:  You cannot read from System.in.");
				throw new AccessControlException(
						"Preventing " + robotProxy.getStatics().getName() + " from access: " + perm);

			}

		}

		// Permission denied.
		syserr.println("Preventing " + robotProxy.getStatics().getName() + " from access: " + perm);

		robotProxy.drainEnergy();

		if (perm instanceof java.awt.AWTPermission) {
			if (perm.getName().equals("showWindowWithoutWarningBanner")) {
				throw new ThreadDeath();
			}
		}

		throw new AccessControlException("Preventing " + Thread.currentThread().getName() + " from access: " + perm);
	}

	public static boolean isSafeThreadSt() { 
		RobocodeSecurityManager rsm = (RobocodeSecurityManager) System.getSecurityManager();

		return rsm.isSafeThread();
	}

	private boolean isSafeThread() {
		return isSafeThread(Thread.currentThread());
	}

	private boolean isSafeThread(Thread c) {
		return threadManager.isSafeThread(c);
	}

	private boolean isSafeContext() {
		try {
			final Object currentContext = getSecurityContext();

			return currentContext.equals(safeSecurityContext);
		} catch (Exception e) {
			syserr.println("Exception checking safe thread: ");
			e.printStackTrace(syserr);
			return false;
		}
	}

	@Override
	public void checkPackageAccess(String pkg) {
		if (!enabled) {
			return;
		}
		if (pkg.equals("java.lang")) {
			return;
		}
		if (pkg.equals("java.util")) {
			return;
		}
		if (isSafeThread()) {
			return;
		}
		if (isSafeContext()) {
			return;
		}
		super.checkPackageAccess(pkg);

		// Access to robocode sub package?
		if (pkg.startsWith("robocode.")) {

			String subPkg = pkg.substring(9);

			if (!alowedPackages.contains(subPkg)) {

				Thread c = Thread.currentThread();

				IHostedThread robotProxy = threadManager.getLoadedOrLoadingRobotProxy(c);

				if (robotProxy != null) {
					robotProxy.drainEnergy();
					if (!experimental && subPkg.equals("robotinterfaces.peer")) {
						robotProxy.println(
								"SYSTEM: " + robotProxy.getStatics().getName()
								+ " is not allowed to access the internal Robocode package: " + pkg + "\n"
								+ "SYSTEM: Perhaps you did not set the -DEXPERIMENTAL=true option in the robocode.bat or robocode.sh file?\n"
								+ "SYSTEM: ----");
					}
				}

				throw new AccessControlException(
						"Preventing " + Thread.currentThread().getName() + " from access to the internal Robocode pakage: "
						+ pkg);
			}
		}
	}

	public static Object createNewAppContext() {
		// same as SunToolkit.createNewAppContext();
		// we can't assume that we are always on Suns JVM, so we can't reference it directly
		// why we call that ? Because SunToolkit is caching AWTQueue instance form main thread group and use it on robots threads
		// and he is not asking us for checkAwtEventQueueAccess above
		try {
			final Class<?> sunToolkit = ClassLoader.getSystemClassLoader().loadClass("sun.awt.SunToolkit");
			final Method createNewAppContext = sunToolkit.getDeclaredMethod("createNewAppContext");

			return createNewAppContext.invoke(null);
		} catch (ClassNotFoundException e) {
			// we are not on sun JVM
			return -1;
		} catch (NoSuchMethodException e) {
			throw new Error("Looks like SunVM but unable to assure secured AWTQueue, sorry", e);
		} catch (InvocationTargetException e) {
			throw new Error("Looks like SunVM but unable to assure secured AWTQueue, sorry", e);
		} catch (IllegalAccessException e) {
			throw new Error("Looks like SunVM but unable to assure secured AWTQueue, sorry", e);
		}
		// end: same as SunToolkit.createNewAppContext();
	}

	public static boolean disposeAppContext(Object appContext) {
		// same as AppContext.dispose();
		try {
			final Class<?> sunToolkit = ClassLoader.getSystemClassLoader().loadClass("sun.awt.AppContext");
			final Method dispose = sunToolkit.getDeclaredMethod("dispose");

			dispose.invoke(appContext);
			return true;
		} catch (ClassNotFoundException ignore) {} catch (NoSuchMethodException ignore) {} catch (InvocationTargetException ignore) {} catch (IllegalAccessException ignore) {}
		return false;
		// end: same as AppContext.dispose();
	}

}