/*
    hsperfdata - a munin plugin reading the hsperfdata to display JVM runtime infos
    Copyright (C) 2016  Stefan "Bebbo" Franke

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package hsperfdata;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.TreeMap;

/**
 * This munin plugin must be run as root.
 * 
 * @author Stefan "Bebbo" Franke
 */
public class Main {

	private static byte buffer[];
	private static int pos;

	public static void main(String[] args) {
		int err = run(args);
		switch (err) {
		case 0: // OK
			break;
		case 1:
			System.err.println("invalid argument(s)");
			System.err.println("USAGE: java -jar hsperfdata.jar <params>");
			System.err.println("  with <params>:");
			System.err.println("    --help       display this info");
			System.err.println("    --showprops  display all properties/values");
			System.err.println("    [config] [<name[+]=property>*] [<folder>*] [<graphName>|<title>|<vlabel>|<used props>");
			break;
		case 2:
			System.err.println("file '" + args[0] + "' does not exist");
			break;
		case 3:
			System.err.println("can't read file '" + args[0] + "' could not open for reading");
			break;
		case 4:
			System.err.println("can't read file '" + args[0] + "' wrong format");
			break;
		default:
			System.err.println("something went wrong");
		}

		System.exit(err);
	}

	private static int run(String[] args) {
		boolean isConfig = false;
		final ArrayList<String> graphs = new ArrayList<String>();
		final ArrayList<String> titles = new ArrayList<String>();
		final ArrayList<String> vlabels = new ArrayList<String>();
		final ArrayList<String> orders = new ArrayList<String>();
		final TreeMap<String, String> props = new TreeMap<String, String>();
		final HashSet<String> counters = new HashSet<String>();
		final ArrayList<File> folders = new ArrayList<File>();
		
		boolean showProps = false;
		for (final String arg : args) {
			if (arg.equals("--help"))
				return 1;

			if (arg.equals("--showprops")) {
				showProps = true;
				continue;
			}

			if (arg.equals("config")) {
				isConfig = true;
				continue;
			}

			final int eq = arg.indexOf('=');
			if (eq > 0) {
				final String name = arg.substring(eq + 1);
				String prop = arg.substring(0, eq);
				
				if (prop.endsWith("+")) {
					prop = prop.substring(0, prop.length() - 1);
					counters.add(prop);
				}
				
				props.put(name, prop);
				continue;
			}

			final int l = arg.indexOf('|');
			if (l > 0) {
				final String graph = arg.substring(0, l);
				String rest = arg.substring(l + 1);

				String title = "title of " + graph;
				int l2 = rest.indexOf('|');
				if (l2 > 0) {
					title = rest.substring(0, l2);
					rest = rest.substring(l2 + 1);
				}
				String vlabel = "units";
				int l3 = rest.indexOf('|');
				if (l3 > 0) {
					vlabel = rest.substring(0, l3);
					rest = rest.substring(l3 + 1);
				}

				graphs.add(graph);
				titles.add(title);
				vlabels.add(vlabel);
				orders.add(rest);
				continue;
			}

			final File folder = new File(arg);
			if (!folder.exists()) {
				System.err.println("skipping folder: " + folder);
			} else {
				folders.add(folder);
			}
		}

		if (folders.isEmpty())
			return 0;

		if (showProps)
			props.clear();;

		// read all values
		final TreeMap<String, TreeMap<String, String>> result = new TreeMap<String, TreeMap<String, String>>();
		for (final File folder : folders) {
			final String folderName = folder.getName();
			final int under = folderName.lastIndexOf('_');
			final String prefix = under > 0 ? folderName.substring(under + 1) : "";

			final File[] afiles = folder.listFiles();
			if (afiles != null) {
				final ArrayList<File> files = new ArrayList<File>();
				final String self = ManagementFactory.getRuntimeMXBean().getName();
				String pid = "";
				if (self != null) {
					int at = self.indexOf('@');
					pid = self.substring(0, at);
				}

				for (final File file : afiles) {
					if (!file.getName().equals(pid))
						files.add(file);
				}

				for (final File file : files) {
					if (file.isDirectory())
						continue;
					final String name = files.size() > 1 ? prefix + "_" + file.getName() : prefix;

					int r = parseFile(file, name, props, result);
					if (r != 0)
						return r;
				}
			}
		}

		if (showProps)
			return 0;
	
		
		for (int i = 0; i < graphs.size(); ++i) {
			final String graph = graphs.get(i);
			final String title = titles.get(i);
			final String vlabel = vlabels.get(i);
			final String used = orders.get(i);
			final String[] orderedProps = used.split(",");

			System.out.println("multigraph hsperf_" + graph);
			if (isConfig) {
				System.out.println("graph_title " + title);
				System.out.println("graph_vlabel " + vlabel);
				System.out.println("graph_category hsperf");
				for (final String name : result.keySet()) {
					for (final String prop : orderedProps) {
						System.out.println(name + "_" + prop + ".label " + name + " " + prop);
						if (counters.contains(prop)) {
							System.out.println(name + "_" + prop + ".type COUNTER");
							System.out.println(name + "_" + prop + ".min 0");
						}
					}
				}
			} else {
				for (final Entry<String, TreeMap<String, String>> e : result.entrySet()) {
					final String name = e.getKey();
					final TreeMap<String, String> tm = e.getValue();
					for (final String prop : orderedProps) {
						final String value = tm.get(prop);
						if (value != null)
							System.out.println(name + "_" + prop + ".value " + value);
					}
				}
			}
			System.out.println();
		}

		return 0;
	}

	private static int parseFile(File file, String prefix, TreeMap<String, String> props,
			TreeMap<String, TreeMap<String, String>> result) {
		if (!file.exists())
			return 2;

		if (props.isEmpty())
			System.out.println(file.getAbsolutePath());
		
		try {
			buffer = readShared(file);

			pos = 0;
			final int value = readInt(); // cookie
			if (value != 0xc0c0feca)
				return 4;

			readInt(); // dunno
			final int length = readInt();
			if (length > buffer.length)
				return 4;

			readInt(); // dunno
			readInt(); // dunno
			readInt(); // dunno
			readInt(); // dunno
			int count = readInt(); // dunno
			while (count-- > 0) {
				final int start = pos;
				final int len = readInt();

				if (start + len > length)
					return 4;

				final int nameStart = readInt();
				if (nameStart + len > length)
					return 4;

				final int slen = readInt();
				final int kind = readInt();

				final int valStart = readInt();
				if (valStart + len > length || valStart < nameStart)
					return 4;

				pos = start + nameStart;
				final int nameLen = valStart - nameStart;

				final String propName = readName(nameLen);
				final String name = props.get(propName);
				String s = "";
				int type = kind & 0xff;
				switch (type) {
				case 0x4a:
					long n = readLong();
					s += n;
					break;
				case 66:
					s = readName(slen);
					break;
				default:
					s = "0";
				}
				if (name != null) {
					TreeMap<String, String> tm = result.get(prefix);
					if (tm == null) {
						tm = new TreeMap<String, String>();
						result.put(prefix, tm);
					}
					tm.put(name, s);
				}
				if (props.isEmpty())
					System.out.println(propName + ": " + s);

				pos = start + len;
			}

		} catch (IOException e) {
			return 3;
		}
		if (props.isEmpty())
			System.out.println();
		return 0;
	}

	private static byte[] readShared(File file) throws IOException {
		byte buffer[] = new byte[(int) file.length()];
		final FileChannel fc = FileChannel.open(file.toPath(), EnumSet.of(StandardOpenOption.READ));
		final ByteBuffer dst = ByteBuffer.wrap(buffer);
		fc.read(dst);
		fc.close();
		return buffer;
	}

	private static String readName(int nameLen) {
		StringBuffer sb = new StringBuffer();
		while (nameLen-- > 0) {
			int ch = buffer[pos++] & 0xff;
			if (ch != 0)
				sb.append((char) ch);
		}
		return sb.toString();
	}

	private static long readLong() {
		long v = 0xff & buffer[pos++];
		v |= (0xff & buffer[pos++]) << 8;
		v |= (0xff & buffer[pos++]) << 16;
		v |= (0xffL & buffer[pos++]) << 24;
		v |= (0xffL & buffer[pos++]) << 32;
		v |= (0xffL & buffer[pos++]) << 40;
		v |= (0xffL & buffer[pos++]) << 48;
		v |= (0xffL & buffer[pos++]) << 56;
		return v;
	}

	private static int readInt() {
		int v = 0xff & buffer[pos++];
		v |= (0xff & buffer[pos++]) << 8;
		v |= (0xff & buffer[pos++]) << 16;
		v |= (0xff & buffer[pos++]) << 24;
		return v;
	}

}
