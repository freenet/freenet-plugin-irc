/* This code is part of a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 3 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */


package plugin.frirc;

/**
 * IRC server manages:
 * - channelmanagers
 * - private conversations
 */

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Map.Entry;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactoryConfigurationError;

import freenet.pluginmanager.PluginRespirator;

public class IRCServer extends Thread {  
	static final int PORT = 6667; // assign to next available Port.
	static final String SERVERNAME = "freenetIRCserver";
	private PluginRespirator pr;
	private ServerSocket serverSocket;
	private IdentityManager identityManager;
	
	//local outgoing connections
	private HashMap<HashMap<String, String>, LocalClient> locals = new HashMap<HashMap<String, String>, LocalClient>();
	private List<ChannelManager> channels = new ArrayList<ChannelManager>();
	
	
	public IRCServer(PluginRespirator pr)
	{
		this.pr = pr;
		this.identityManager = new IdentityManager(pr, null);
	}

	/**
	 * Set a specific mode for a user in some channel
	 * @param source The connection from which the modeset originates (used for distributing the outgoinig messages to the correct channel)
	 * @param nick The nick to which the modeset should be applied
	 * @param channel The channel in which the nick resides
	 * @param mode The actual modeset change ('+v' etc)
	 */
	/*
	public void setUserChannelMode(FrircConnection source, String nick, String channel, String mode)
	{
		System.out.println("Setting mode " + mode + " for nick: " + nick);
		outQueue.get(source).add(new Message(":" + SERVERNAME + " MODE " + channel + " " + mode + " " +nick));
	}
	
	*/
	

	/**
	 * Send a message to locally clients
	 */
	
	public void sendLocalMessage(Message message, Map<String, String> identity)
	{
		locals.get(identity).sendMessage(message);
	}

	/**
	 * Find an identity through its socketConnection object
	 * @param source
	 * @return
	 */
	
	private Map<String, String> getIdentityByConnection(LocalClient source)
	{
		for(Entry<HashMap<String, String>, LocalClient> pair : locals.entrySet())
		{
			if (pair.getValue() == source)
			{
				return pair.getKey();
			}
		}
		return null;
	}
	
	/**
	 * Retrieve a ChannelManager by means of a channelString
	 * @param channel
	 * @return
	 */
	
	private ChannelManager getChannelManager(String channel)
	{
		ChannelManager manager = null;
		for(ChannelManager channelManagerItem : channels)
		{
			if (channelManagerItem.getChannel().equals(channel)) manager = channelManagerItem;
		}
		if (manager == null)
		{
			manager = new ChannelManager(channel, this, pr);
			channels.add(manager);
		}
		return manager;
	}
	
	
	public void sendAllLocalClientsInChannel(ChannelManager manager, Message message)
	{
		for(HashMap<String, String> identityItem : locals.keySet())
		{
			if (manager.inChannel(identityItem))
			{
				locals.get(identityItem).sendMessage(message);
			}
		}
	}
	
	
	/**
	 * Process and possibly reply to IRC messages
	 * @param source
	 * @param message
	 */
	
	public synchronized void message(LocalClient source, Message message)
	{
		/**
		 * NICK
		 */
		
		//associate nick with connection
		if (message.getType().equals("NICK") && !message.getNick().equals(""))
		{	
			//remove old identity map
			Map<String, String> old_identity = getIdentityByConnection(source);
			HashMap<String, String> new_identity = identityManager.getIdentityByNick(message.getNick());
			locals.remove(old_identity);
			locals.put(new_identity, source);
			
			if (old_identity != null) // we are dealing with a nickCHANGE
			{
				//confirm the nickchange to all local clients
				for(LocalClient local : locals.values())
				{
					local.sendMessage(Message.createNickChangeMessage(old_identity, new_identity));
				}
			}
			
			//tell client if nick is not a known a WoT identity that we know the user has
			if (!identityManager.getOwnIdentities().contains(new_identity))
			{
				source.sendMessage(Message.createServerNoticeMessage(message.getNick(), "Could not associate that nick with a WoT identity. Reload the plugin if you just added it or check whether it is actually correct. Joining channels will NOT work!"));
				source.sendMessage(new Message("QUIT"));
			}
		}


		/**
		 * USER
		 * Process the server login messages after USER
		 */
		else if (message.getType().equals("USER") && !message.getValue().equals(""))
		{
			for(Message loginMessage : Message.createGenericServerLoginMessages(getIdentityByConnection(source)))
			{
				source.sendMessage(loginMessage);
			}
		}

		/**
		 * QUIT
		 * Process the QUIT signal (disconnect local connection)
		 */
		else if (message.getType().equals("QUIT"))
		{
			source.sendMessage(new Message("QUIT"));
			try {
				source.getSocket().close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		/**
		 * MODE
		 */
		
		else if (message.getType().equals("MODE"))
		{
			source.sendMessage(Message.createServerNoticeMessage(message.getNick(), "Modes not supported at this time."));
		}

		
		/**
		 * Join a channel
		 */

		else if (message.getType().equals("JOIN") && !message.getChannel().equals(""))
		{
			//retrieve the nick associated with the connection
			String channel = message.getChannel();
			HashMap<String, String> identity = (HashMap<String, String>) getIdentityByConnection(source);
			
			ChannelManager manager = getChannelManager(channel); 
			manager.addIdentity(identity);
			
			//inform all localClients in the same channel that the user has joined
			sendAllLocalClientsInChannel(manager, Message.createJOINMessage(identity, channel)); 
			
			//inform the joining client about who is in the channel
			source.sendMessage(Message.createChannelModeMessage(channel));
			for(Message messageItem : Message.createChannelJoinNickList(identity, channel, manager.getIdentities()))
			{
				source.sendMessage(messageItem);
			}
		}
		
		/**
		 * PING
		 */
		
		else if (message.getType().equals("PING"))
		{
			source.sendMessage(new Message("PONG " + message.getValue()));
		}

		/**
		 * PART
		 */
		
		else if (message.getType().equals("PART"))
		{
			ChannelManager manager = getChannelManager(message.getChannel());
			HashMap<String, String> identity = (HashMap<String, String>) getIdentityByConnection(source);
			
			//inform all localClients in the same channel that the user has left
			sendAllLocalClientsInChannel(manager, Message.createPartMessage(identity, message.getChannel()));
			manager.removeIdentity(identity);
		}
		
		
		
		
		/*
		 * WHO
		 */
		
		
		/*
		
		else if (message.getType().equals("WHO"))
		{
			String nick = getNickByCon(source);
			String channel = message.getChannel();

			for(String channelUser: channelUsers.get(message.getChannel()))
			{
				outQueue.get(source).add(new Message(":" + SERVERNAME + " 352 " + channelUser + " freenet " + channelUser + " H :0 " + channelUser));
			}

			outQueue.get(source).add(new Message("315 " + nick + " " + channel + " :End of /WHO list."));
		}

		*/

		/**
		 * Message for channel
		 */

		/*
		else if (message.getType().equals("PRIVMSG")) 
		{
			String nick = getNickByCon(source);

			//iterate over all the users(connections) and send them the privmsg, except the originating user!

			for(String channelUser: channelUsers.get(message.getChannel()))
			{
				if (nickToInput.get(channelUser) != null)
				{
					for (FrircConnection out : nickToInput.get(channelUser))
					{
						if (	( !source.isLocal() && out.isLocal() && !channelUser.equals(nick)) ||	//deliver locally 
								( source.isLocal() && !out.isLocal() && channelUser.equals(nick) )		//publish to freenet
							) 
						{
							System.out.println("DEBUG: Message added to some outqueue");
							outQueue.get(out).add(new Message(":" + nick + "@freenet PRIVMSG " + message.getChannel() + " :" + message.getValue()));
							
							break;
							//TODO: probably some memory leak here...(outQueue never cleaned up)
						}
					}
				}
			}
		}
	
		*/
	
	}

	public void run()
	{
		try {
			serverSocket = new ServerSocket(PORT);

			InetAddress  addrs= InetAddress.getLocalHost();         
			// Or InetAddress  addrs= InetAddress.getByName("localhost");
			// Or InetAddress  addrs= InetAddress.getByName("127.0.0.1");  

			System.out.println("TCP/Server running on : "+ addrs +" ,Port "+serverSocket.getLocalPort());

			while(true) {
				// Blocks until a connection occurs:
				Socket socket = serverSocket.accept();
				try {
					new LocalClient(socket, this);  // Handle an incoming Client.
				} catch(IOException e) {
					// If it fails, close the socket,
					// otherwise the thread will close it:
					socket.close();
					serverSocket.close();
					
					System.out.println("Closing IRC server...");
					locals.clear();
					channels.clear();
					
					
					return;
				} catch (TransformerConfigurationException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (ParserConfigurationException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (TransformerFactoryConfigurationError e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
	}

	public void terminate()
	{
		if (serverSocket != null)
		{
		try {
			serverSocket.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		}
	
		//do something smart with clearing channels and signalling threads to stop listening etc
	}

	public boolean stopThread()
	{
		if (serverSocket == null || serverSocket.isClosed()) return true;
		return false;
	}
	
}
