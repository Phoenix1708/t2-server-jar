/*
 * Copyright (c) 2010-2012 The University of Manchester, UK.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the names of The University of Manchester nor the names of its
 *   contributors may be used to endorse or promote products derived from this
 *   software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package uk.org.taverna.server.client;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.sf.practicalxml.ParseUtil;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import uk.org.taverna.server.client.connection.UserCredentials;
import uk.org.taverna.server.client.connection.Connection;
import uk.org.taverna.server.client.connection.ConnectionFactory;
import uk.org.taverna.server.client.connection.params.ConnectionParams;

/**
 * The Server class represents a connection to a Taverna Server instance
 * somewhere on the Internet. Only one instance of this class is created for
 * each Taverna Server instance.
 * 
 * To make a connection to a server call {@link Server#connect(URI)} with it
 * full URL as the parameter. If there already exists a Server instance that is
 * connected to this Taverna Server then it will be returned, otherwise a new
 * Server instance is created and returned.
 * 
 * @author Robert Haines
 */
public final class Server {
	private final Connection connection;

	private final URI uri;
	private final float version;
	private final Map<UUID, Run> runs;

	private final Map<String, String> links;

	private final XmlUtils xmlUtils;

	/**
	 * 
	 * @param uri
	 * @param params
	 */
	public Server(URI uri, ConnectionParams params) {
		// strip out username and password if present in server URI
		String userInfo = uri.getUserInfo();
		if (userInfo != null) {
			try {
				this.uri = new URI(uri.getScheme(), null, uri.getHost(),
						uri.getPort(), uri.getPath(), null, null);
			} catch (URISyntaxException e) {
				throw new IllegalArgumentException("Bad URI passed in: " + uri);
			}
		} else {
			this.uri = uri;
		}

		connection = ConnectionFactory.getConnection(this.uri, params);
		xmlUtils = XmlUtils.getInstance();

		// add a slash to the end of this address to work around this bug:
		// http://www.mygrid.org.uk/dev/issues/browse/TAVSERV-113
		String restPath = this.uri.toASCIIString() + "/rest/";
		Document doc = ParseUtil.parse(new String(connection.getAttribute(
				restPath, null)));
		version = getServerVersion(doc);
		links = getServerDescription(doc);

		// System.out.println("u: " + this.uri);
		// System.out.println("v: " + version);
		// for (String s : links.keySet()) {
		// System.out.println(s + ": " + links.get(s));
		// }

		// initialise run list
		runs = new HashMap<UUID, Run>();
		// getRunsFromServer();
	}

	/**
	 * 
	 * @param uri
	 */
	public Server(URI uri) {
		this(uri, null);
	}

	/**
	 * Get the version of the remote Taverna Server instance.
	 * 
	 * @return the Taverna Server version.
	 */
	public float getVersion() {
		return version;
	}

	/**
	 * Get the Run instance hosted by this Server by its UUID.
	 * 
	 * @param uuid
	 *            The UUID of the Run instance to get.
	 * @return the Run instance.
	 */
	public Run getRun(UUID uuid, UserCredentials credentials) {
		return getRunsFromServer(credentials).get(uuid);
	}

	/**
	 * Get all the Run instances hosted on this server.
	 * 
	 * @return all the Run instances hosted on this server.
	 */
	public Collection<Run> getRuns(UserCredentials credentials) {
		return getRunsFromServer(credentials).values();
	}

	/**
	 * Delete a Run from the server.
	 * 
	 * @param uuid
	 *            The UUID of the run to delete.
	 */
	public void deleteRun(UUID uuid, UserCredentials credentials) {
		try {
			connection.delete(links.get("runs") + "/" + uuid, credentials);
		} catch (AccessForbiddenException e) {
			if (getRunsFromServer(credentials).containsKey(uuid)) {
				throw e;
			} else {
				throw new RunNotFoundException(uuid);
			}
		}
	}

	/**
	 * Delete a Run from the server.
	 * 
	 * @param run
	 *            The Run instance to delete.
	 */
	public void deleteRun(Run run, UserCredentials credentials) {
		deleteRun(run.getUUID(), credentials);
	}

	/**
	 * Delete all runs on this server instance.
	 */
	public void deleteAllRuns(UserCredentials credentials) {
		for (Run run : getRuns(credentials)) {
			run.delete();
		}
	}

	private Map<UUID, Run> getRunsFromServer(UserCredentials credentials) {
		String runList = new String(connection.getAttribute(links.get("runs"),
				credentials));
		Document doc = ParseUtil.parse(runList);

		// add new runs, but keep a list of the new
		// UUIDs so we can remove the stale ones below.
		UUID uuid;
		ArrayList<UUID> uuids = new ArrayList<UUID>();
		for (Element e : xmlUtils.evalXPath(doc, "//nsr:run")) {
			uuid = UUID.fromString(e.getTextContent());
			uuids.add(uuid);
			if (!runs.containsKey(uuid)) {
				runs.put(uuid, new Run(this, uuid, credentials));
			}
		}

		// any UUIDS in the runs list that aren't in the list we've
		// just got from the server are dead and can be removed.
		if (runs.size() > uuids.size()) {
			for (UUID u : runs.keySet()) {
				if (!uuids.contains(u)) {
					runs.remove(u);
				}
			}
		}

		assert (runs.size() == uuids.size());

		return runs;
	}

	private float getServerVersion(Document doc) {
		String version = xmlUtils.evalXPath(doc, "/nsr:serverDescription",
				"nss:serverVersion");

		if (version.equalsIgnoreCase("")) {
			return 1.0f;
		} else {
			return Float.parseFloat(version.substring(0, 3));
		}
	}

	private Map<String, String> getServerDescription(Document doc) {
		HashMap<String, String> links = new HashMap<String, String>();

		links.put("runs", xmlUtils.evalXPath(doc, "//nsr:runs", "xlink:href"));

		if (version > 1.0) {
			String policy = xmlUtils.evalXPath(doc, "//nsr:policy",
					"xlink:href");

			links.put("policy", policy);
			doc = ParseUtil.parse(new String(connection.getAttribute(policy,
					null)));

			links.put("permlisteners", xmlUtils.evalXPath(doc,
					"//nsr:permittedListenerTypes", "xlink:href"));

			links.put("notifications", xmlUtils.evalXPath(doc,
					"//nsr:enabledNotificationFabrics", "xlink:href"));
		} else {
			links.put("permlisteners", xmlUtils.evalXPath(doc,
					"//nsr:permittedListeners", "xlink:href"));
		}

		links.put("runlimit",
				xmlUtils.evalXPath(doc, "//nsr:runLimit", "xlink:href"));
		links.put("permworkflows", xmlUtils.evalXPath(doc,
				"//nsr:permittedWorkflows", "xlink:href"));

		return links;
	}

	/**
	 * Connect to a Taverna Server.
	 * 
	 * @param uri
	 *            The address of the server to connect to in the form
	 *            http://server:port/location
	 * @return a Server instance representing the connection to the specified
	 *         Taverna Server.
	 * @throws URISyntaxException
	 *             if the provided URI is badly formed.
	 */
	public static Server connect(String uri) throws URISyntaxException {
		return new Server(new URI(uri));
	}

	/**
	 * Connect to a Taverna Server.
	 * 
	 * @param uri
	 *            The URI of the server to connect to.
	 * @return a Server instance representing the connection to the specified
	 *         Taverna Server.
	 */
	public static Server connect(URI uri) {
		return new Server(uri);
	}

	/**
	 * Get the URI of this server instance.
	 * 
	 * @return the URI of this server instance.
	 */
	public URI getUri() {
		return uri;
	}

	/**
	 * Get the URI of this server instance as a String.
	 * 
	 * @return the URI of this server instance as a String.
	 */
	public String getStringUri() {
		return uri.toASCIIString();
	}

	/**
	 * Get the maximum number of run that this server can host concurrently.
	 * 
	 * @return the maximum number of run that this server can host concurrently.
	 */
	public int getRunLimit(UserCredentials credentials) {
		return Integer.parseInt(new String(connection.getAttribute(
				links.get("runlimit"), credentials)).trim());
	}

	/**
	 * Initialize a Run on this server instance.
	 * 
	 * @param workflow
	 *            the workflow to be run.
	 * @return the UUID of the new run as returned by the server.
	 */
	UUID initializeRun(String workflow, UserCredentials credentials) {
		UUID uuid = null;
		String location = connection.upload(links.get("runs"),
				xmlUtils.buildXMLFragment("workflow", workflow), credentials);

		if (location != null) {
			uuid = UUID
					.fromString(location.substring(location.lastIndexOf("/") + 1));
		}

		return uuid;
	}

	/**
	 * Create a new Run on this server with the supplied workflow.
	 * 
	 * @param workflow
	 *            the workflow to be run.
	 * @return a new Run instance.
	 */
	public Run createRun(String workflow, UserCredentials credentials) {
		return new Run(this, workflow, credentials);
	}

	/**
	 * Create a new Run on this server with the supplied workflow file.
	 * 
	 * @param workflow
	 *            the workflow file to be run.
	 * @return a new Run instance.
	 */
	public Run createRun(File workflow, UserCredentials credentials)
			throws IOException {
		return new Run(this, workflow, credentials);
	}

	/**
	 * Set an input port on a run.
	 * 
	 * @param run
	 *            the Run.
	 * @param input
	 *            the name of the input port.
	 * @param value
	 *            the value to set the input port to be.
	 */
	public void setRunInput(Run run, String input, String value,
			UserCredentials credentials) {
		String path = run.getInputsPath() + "/input/" + input;
		try {
			connection.setAttribute(path,
					xmlUtils.buildXMLFragment("inputvalue", value),
					"application/xml", credentials);
		} catch (AttributeNotFoundException e) {
			UUID uuid = run.getUUID();
			if (getRunsFromServer(credentials).containsKey(uuid)) {
				throw e;
			} else {
				throw new RunNotFoundException(uuid);
			}
		}
	}

	/**
	 * Set an input port on a run to use a file as its input. This file should
	 * already have been uploaded to the server.
	 * 
	 * @param run
	 *            the Run.
	 * @param input
	 *            the name of the input port.
	 * @param filename
	 *            the filename to use as input.
	 * @see #uploadRunFile(Run, File, String)
	 * @see #uploadRunFile(UUID, File, String)
	 */
	public void setRunInputFile(Run run, String input, String filename,
			UserCredentials credentials) {
		String path = run.getInputsPath() + "/input/" + input;
		try {
			connection.setAttribute(path,
					xmlUtils.buildXMLFragment("inputfile", filename),
					"application/xml", credentials);
		} catch (AttributeNotFoundException e) {
			UUID uuid = run.getUUID();
			if (getRunsFromServer(credentials).containsKey(uuid)) {
				throw e;
			} else {
				throw new RunNotFoundException(uuid);
			}
		}
	}

	/**
	 * Read attribute data from a run.
	 * 
	 * @param uuid
	 *            the UUID of the run.
	 * @param uri
	 *            the full URI of the attribute to get.
	 * @param type
	 *            the mime type of the attribute being retrieved.
	 * @return the data associated with the attribute.
	 */
	public byte[] getRunData(UUID uuid, String uri, String type,
			UserCredentials credentials) {
		try {
			return connection.getAttribute(uri, type, credentials);
		} catch (AttributeNotFoundException e) {
			if (getRunsFromServer(credentials).containsKey(uuid)) {
				throw e;
			} else {
				throw new RunNotFoundException(uuid);
			}
		}
	}

	/**
	 * Read attribute data from a run.
	 * 
	 * @param run
	 *            the Run instance
	 * @param uri
	 *            the full URI of the attribute to get.
	 * @param type
	 *            the mime type of the attribute being retrieved.
	 * @return the data associated with the attribute.
	 */
	public byte[] getRunData(Run run, String uri, String type,
			UserCredentials credentials) {
		return getRunData(run.getUUID(), uri, type, credentials);
	}

	/**
	 * Read an attribute, of a specific type, of a run.
	 * 
	 * @param run
	 *            the Run instance
	 * @param uri
	 *            the full URI of the attribute to get.
	 * @param type
	 *            the mime type of the attribute being retrieved.
	 * @return the attribute as a String.
	 */
	public String getRunAttribute(Run run, String uri, String type,
			UserCredentials credentials) {
		return new String(getRunData(run.getUUID(), uri, type, credentials));
	}

	/**
	 * Read an attribute of a run.
	 * 
	 * @param run
	 *            the Run instance
	 * @param uri
	 *            the full URI of the attribute to get.
	 * @return the attribute as a String.
	 */
	public String getRunAttribute(Run run, String uri,
			UserCredentials credentials) {
		return new String(getRunData(run.getUUID(), uri, null, credentials));
	}

	/**
	 * Set a run's attribute to a new value.
	 * 
	 * @param uuid
	 *            the UUID of the run.
	 * @param uri
	 *            the full URI of the attribute to set.
	 * @param value
	 *            the new value of the attribute.
	 */
	public void setRunAttribute(UUID uuid, String uri, String value,
			UserCredentials credentials) {
		try {
			connection.setAttribute(uri, value, "text/plain", credentials);
		} catch (AttributeNotFoundException e) {
			if (getRunsFromServer(credentials).containsKey(uuid)) {
				throw e;
			} else {
				throw new RunNotFoundException(uuid);
			}
		}
	}

	/**
	 * Set a run's attribute to a new value.
	 * 
	 * @param run
	 *            the Run instance.
	 * @param uri
	 *            the full URI of the attribute to set.
	 * @param value
	 *            the new value of the attribute.
	 */
	public void setRunAttribute(Run run, String uri, String value,
			UserCredentials credentials) {
		setRunAttribute(run.getUUID(), uri, value, credentials);
	}

	String getRunDescription(Run run, UserCredentials credentials) {
		return getRunAttribute(run, links.get("runs") + "/" + run.getUUID(),
 credentials);
	}

	/**
	 * Upload a file to the server for use by a run.
	 * 
	 * @param uuid
	 *            the UUID of the run to upload to.
	 * @param file
	 *            the file to upload.
	 * @param uploadLocation
	 *            the location to upload to. This should be the full URI.
	 * @param rename
	 *            optionally rename the file at the remote location. Pass null
	 *            or the empty string to ignore.
	 * @return the name of the file on the remote server. This will be unchanged
	 *         unless rename was used.
	 * @throws IOException
	 * @see #uploadRunFile(UUID, File, String)
	 * @see #uploadRunFile(Run, File, String, String)
	 * @see #uploadRunFile(Run, File, String)
	 */
	public String uploadRunFile(UUID uuid, File file, String uploadLocation,
			String rename, UserCredentials credentials) throws IOException {

		if (rename == null || rename.equals("")) {
			rename = file.getName();
		}

		byte[] data = FileUtils.readFileToByteArray(file);
		String contents = Base64.encodeBase64String(data);

		connection.upload(uploadLocation,
				xmlUtils.buildXMLFragment("upload", rename, contents),
				credentials);

		return rename;
	}

	/**
	 * Upload a file to the server for use by a run.
	 * 
	 * @param uuid
	 *            the UUID of the run to upload to.
	 * @param file
	 *            the file to upload.
	 * @param uploadLocation
	 *            the location to upload to. This should be the full URI.
	 * @return the name of the file on the remote server. This will be unchanged
	 *         unless rename was used.
	 * @throws IOException
	 * @see #uploadRunFile(UUID, File, String, String)
	 * @see #uploadRunFile(Run, File, String, String)
	 * @see #uploadRunFile(Run, File, String)
	 */
	public String uploadRunFile(UUID uuid, File file, String uploadLocation,
			UserCredentials credentials) throws IOException {
		return uploadRunFile(uuid, file, uploadLocation, null, credentials);
	}

	/**
	 * Upload a file to the server for use by a run.
	 * 
	 * @param run
	 *            the Run instance to upload to.
	 * @param file
	 *            the file to upload.
	 * @param uploadLocation
	 *            the location to upload to. This should be the full URI.
	 * @param rename
	 *            optionally rename the file at the remote location. Pass null
	 *            or the empty string to ignore.
	 * @return the name of the file on the remote server. This will be unchanged
	 *         unless rename was used.
	 * @throws IOException
	 * @see #uploadRunFile(Run, File, String)
	 * @see #uploadRunFile(UUID, File, String)
	 * @see #uploadRunFile(UUID, File, String, String)
	 */
	public String uploadRunFile(Run run, File file, String uploadLocation,
			String rename, UserCredentials credentials) throws IOException {
		return uploadRunFile(run.getUUID(), file, uploadLocation, rename,
				credentials);
	}

	/**
	 * Upload a file to the server for use by a run.
	 * 
	 * @param run
	 *            the Run instance to upload to.
	 * @param file
	 *            the file to upload.
	 * @param uploadLocation
	 *            the location to upload to. This should be the full URI.
	 * @return the name of the file on the remote server. This will be unchanged
	 *         unless rename was used.
	 * @throws IOException
	 * @see #uploadRunFile(Run, File, String, String)
	 * @see #uploadRunFile(UUID, File, String)
	 * @see #uploadRunFile(UUID, File, String, String)
	 */
	public String uploadRunFile(Run run, File file, String uploadLocation,
			UserCredentials credentials) throws IOException {
		return uploadRunFile(run.getUUID(), file, uploadLocation, null,
				credentials);
	}

	void makeRunDir(UUID uuid, String root, String name,
			UserCredentials credentials) throws IOException {
		if (name.contains("/")) {
			throw new AccessForbiddenException(
					"creation of subdirectories directly (" + name + ")");
		}

		try {
			connection.upload(root, xmlUtils.buildXMLFragment("mkdir", name),
					credentials);
		} catch (AttributeNotFoundException e) {
			if (getRunsFromServer(credentials).containsKey(uuid)) {
				throw e;
			} else {
				throw new RunNotFoundException(uuid);
			}
		}
	}

	void makeRunDir(Run run, String root, String name,
			UserCredentials credentials) throws IOException {
		makeRunDir(run.getUUID(), root, name, credentials);
	}
}
