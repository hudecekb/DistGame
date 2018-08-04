/************************************************
 * 
 *  DistGame
 *  
 *  Barrett Hudecek 2018
 *  
 *  
 * 
 ************************************************/

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

public class DistGame {
  
  private static DefaultListModel<String> openGames;
  private static int height = 500;
  private static int width = 800;
  private static JFrame frame;
  
  private JPanel menuPanel;
  private JTextField nameField;
  private GamePanel gamepanel;
  private Gamestate gamestate;
  
  private DatagramSocket hostSock;
  private MulticastSocket lobbySock, gameSock;
  private boolean inLobby, gameRunning;
  private Thread hostThread = null, clientThread = null,
                 outThread = null, inThread = null;
  
  public static void main(String[]args) {
    
    DistGame game = new DistGame();
    game.createClientThread();
    
  } // main
  
  public DistGame() {
    
    // open window
    SwingUtilities.isEventDispatchThread();
    frame = new JFrame();
    frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    frame.setBounds(50, 50, width, height);
    frame.setVisible(true);
    
    // add window listener to make sure threads close
    frame.addWindowListener(new WindowAdapter() {
      public void windowClosing(WindowEvent event) {
        inLobby = false;
        gameRunning = false;
        try {
          if (hostThread != null) {
            hostThread.join();
          }
          if (clientThread != null) {
            lobbySock.close();
            clientThread.join();
          }
          if (outThread != null) {
            outThread.join();
          }
          if (inThread != null) {
            inThread.join();
          }
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
        System.exit(0);
      }
    });
    
    // create startup menu
    menuPanel = new JPanel();
    menuPanel.setLayout(new BoxLayout(menuPanel, BoxLayout.PAGE_AXIS));
    nameField = new JTextField(10);
    JButton newGameButton = new JButton("New Game");
    newGameButton.setActionCommand("newGame");
    JButton joinGameButton = new JButton("Join Game");
    joinGameButton.setActionCommand("joinGame");
    JLabel openGamesLabel = new JLabel("Open Games:");
    openGames = new DefaultListModel<String>();
    JList<String> gameList = new JList<String>(openGames);
    JScrollPane gameListPane = new JScrollPane(gameList);
    GameListener listener = new GameListener(gameList, nameField, this);
    newGameButton.addActionListener(listener);
    joinGameButton.addActionListener(listener);
    menuPanel.add(nameField);
    menuPanel.add(newGameButton);
    menuPanel.add(joinGameButton);
    menuPanel.add(openGamesLabel);
    menuPanel.add(gameListPane);
    frame.add(menuPanel);
    frame.pack();
    inLobby = true;
    gameRunning = true;
    
  }  // constructor
  
  // replaces startup menu with game window, instantiates gamestate
  public void createGame(InetAddress gameAddr) {
    
    // create gamestate and gamepanel, switch start menu for game view
    inLobby = false;
    Random rand = new Random();
    int myID = rand.nextInt();
    gamestate = new Gamestate(height);
    gamestate.newChar(new Character(0, 0, 0, 0, myID));
    gamepanel = new GamePanel(height, width, gamestate);
    frame.setFocusable(true);
    frame.remove(menuPanel);
    frame.add(gamepanel);
    frame.pack();
        
    // timer ensures that the game updates as regularly as possible
    Timer timer = new Timer();
    GameUpdate update = new GameUpdate();
    timer.schedule(update, 0, 40);
    
    // open socket for exchanging game data
    try {
      gameSock = new MulticastSocket(6007);
      gameSock.joinGroup(gameAddr);
    } catch (IOException e) {
      e.printStackTrace();
    }
    
    // create a thread each to send through gameSock
    outThread = new Thread() {
      @Override
      public void run() {
        
        // allocate needed variables
        ArrayList<Character> charList = gamestate.getCharList();
        byte[] buf;
        String message;
        DatagramPacket packet;

        while (gameRunning) {

          // compose update message
          buf = new byte[512];
          Character ch = charList.get(0);
          synchronized (ch) {
            message = Double.toString(ch.x) + "," + Double.toString(ch.y) +
                "," + Double.toString(ch.dx) + "," + Double.toString(ch.dy) +
                "," + Integer.toString(ch.id);
          }          
          buf = message.getBytes();
          packet = new DatagramPacket(buf, buf.length, gameAddr, 6007);
          
          // send it
          try {
            gameSock.send(packet);
          } catch (IOException e) {
            e.printStackTrace();
          }
        }
        
        // send quit message so other players remove us from their game
        Character ch = charList.get(0);
        message = "-1,0,0,0," + Integer.toString(ch.id);
        buf = message.getBytes();
        packet = new DatagramPacket(buf, buf.length, gameAddr, 6007);
        try {
          gameSock.send(packet);
        } catch (IOException e) {
          e.printStackTrace();
        }
        gameSock.close();
      }
    };
    outThread.start();
    
    // create a thread to receive through gameSock
    inThread = new Thread() {
      @Override
      public void run() {
        
        // allocate needed variables
        byte[] buf;
        String message;
        DatagramPacket packet;
        
        while (gameRunning) {
          
          // receive new message
          buf = new byte[512];
          packet = new DatagramPacket(buf, buf.length);
          try {
            gameSock.receive(packet);
          } catch (SocketException e) {
            // Socket was closed to force this thread to join.
            return;
          } catch (IOException e) {
            e.printStackTrace();
          }
          message = new String(packet.getData(), 0, packet.getLength());
          
          // parse received message
          String[] parts = message.split(",");
          double x = Double.parseDouble(parts[0]);
          double y = Double.parseDouble(parts[1]);
          double dx = Double.parseDouble(parts[2]);
          double dy = Double.parseDouble(parts[3]);
          int id = Integer.parseInt(parts[4]);
          
          // update our gamestate from message
          if (!(id == myID)) {
            if (x == -1) {
              // player quit
              gamestate.removeChar(id);
            } else {
              // update (or add) char
              gamestate.setChar(x, y, dx, dy, id);
            }
          }
        }
      }
    };
    inThread.start();
    
  } // createGame()
  
  // creates a thread to spam invitations to our game (only the host does this)
  public void createHostThread(InetAddress gameAddr, String name) {
    
    hostThread = new Thread() {
      @Override
      public void run() {
        while (inLobby) {
        }
        try {
          // open outgoing socket and create invitation
          hostSock = new DatagramSocket(6006);
          byte[] buf = new byte[512];
          buf = name.getBytes();
          InetAddress lobby = InetAddress.getByName("226.0.226.0");
          DatagramPacket packet = new DatagramPacket(buf, buf.length, lobby, 6005);
          
          // spam invites
          while (gameRunning) {
            hostSock.send(packet);
          }
          
          // tell users in lobby to take us off the game list (3 times to be sure)
          String mes = "CLOSE_GAME";
          buf = mes.getBytes();
          packet = new DatagramPacket(buf, buf.length, lobby, 6005);
          for (int i = 0; i < 3; i++) {
            hostSock.send(packet);
          }
          hostSock.close();
        } catch (SocketException e) {
          e.printStackTrace();
        } catch (UnknownHostException e) {
          e.printStackTrace();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    };
    hostThread.start();
    
  } // createHostThread()
  
  // creates a thread to listen for invitation to existing games
  public void createClientThread() {
    
    clientThread = new Thread() {
      @Override
      public void run() {
        try {
          // open socket and prepare for incoming packets
          lobbySock = new MulticastSocket(6005);
          InetAddress lobby = InetAddress.getByName("226.0.226.0");
          lobbySock.joinGroup(lobby);
          DatagramPacket packet;
          
          // accept packets
          while (inLobby) {
            byte[] buf = new byte[512];
            packet = new DatagramPacket(buf, buf.length);
            lobbySock.receive(packet);
            String gameName = new String(packet.getData(), 0, packet.getLength());
            
            // read packets and add/remove games from list
            if (gameName.endsWith("CLOSE_GAME")) {
              gameName = gameName.substring(0, gameName.length()-10);
              if (openGames.contains(gameName)) {
                openGames.removeElement(gameName);
              }
            } else if (!openGames.contains(gameName)) {
              openGames.addElement(gameName);
            }
          }
          lobbySock.close();
        } catch (SocketException e) {
          // Do nothing. Socket was closed to force this thread to join.
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    };
    clientThread.start();
    
  } // createClientThread()
  
  /******************************************************
   * 
   *  GameUpdate
   *  
   *  repaints the frame and updates character positions
   *  whenever the timer fires
   * 
   ******************************************************/
  class GameUpdate extends TimerTask {
    
    @Override
    public void run() {

      frame.repaint();
      
      ArrayList<Character> charList = gamestate.getCharList();
      for (int i = 0; i < charList.size(); i++) {
        Character ch = charList.get(i);
        synchronized (ch) {
          // update positions
          double x = ch.x + ch.dx;
          double y = ch.y + ch.dy;
        
          // prevent leaving the frame
          if (x > width - 60) {
            x = width - 60;
          } else if (x < 0) {
            x = 0;
          }
          if (y > height - 80) {
            y = height - 80;
            ch.dy = 0;
          } else if (y < 0) {
            y = 0;
          }
          
          // detect collisions and shove the offenders apart
          for (int j = i+1; j < charList.size(); j++) {
            Character ch2 = charList.get(j);
            synchronized (ch2) {
              double xDist = ch.x - ch2.x;
              double yDist = ch.y - ch2.y;
              if (Math.abs(xDist) < 60 && Math.abs(yDist) < 80) {
                if (xDist < 0) {
                  x -= 30;
                  ch2.x += 30;
                } else {
                  x += 30;
                  ch2.x -= 30;
                }
              }
            }
          }
        
          // gravity and momentum
          if (y != (height - 80)) {
            ch.dy += 5;
          }
          
          // store new positions
          ch.x = x;
          ch.y = y;
        }          
      }
        
      frame.repaint();
        
    }
    
  } // GameUpdate  
  
} // DistGame

/********************************************************
*
*  Gamestate
*  
*  contains all the current details of the game
*
********************************************************/
class Gamestate {
  
  private ArrayList<Character> charList;
  private int height;
  
  public Gamestate(int height) {
    
    charList = new ArrayList<Character>();
    this.height = height;
    
  }
  
  public ArrayList<Character> getCharList() {
    return charList;
  }
  
  public void newChar(Character ch) {
    charList.add(ch);
  }
  
  public void removeChar(int id) {
    for (Character ch : charList) {
      if (ch.id == id) {
        synchronized (ch) {
          charList.remove(ch);
        }
      }
    }
  }

  // moves our character when called by actionlistener
  public void moveChar(int code) {
    switch (code) {
      case 0: charList.get(0).setDX(-30);
              break;
      case 1: charList.get(0).setDX(30);
              break;
      case 2: if (charList.get(0).y == (height-80)) {
                charList.get(0).setDY(-50);
              }
              break;
      case 3: 
      case 4: charList.get(0).setDX(0);
    }
  }
  
  // sets all information about a character
  // if character is not in list, adds them
  public void setChar(double x, double y, double dx, double dy, int id) {
    for (Character ch : charList) {
      if (ch.id == id) {
        synchronized (ch) {
          ch.x = x;
          ch.y = y;
          ch.dx = dx;
          ch.dy = dy;
        }
        return;
      }
    }
    this.newChar(new Character(x, y, dx, dy, id));
  }
  
} // Gamestate

/********************************************************
*
*  Character
*  
*  
*  contains location data of a player character
*  id is also used as RGB value for painting the character
*
********************************************************/
class Character {
  
  int id;
  double x, y, dx, dy;
  Color color;
  
  public Character(double x, double y, double dx, double dy, int id) {
    this.x = x;
    this.y = y;
    this.dx = dx;
    this.dy = dy;
    this.id = id;
    color = new Color(id);
  }
  
  public void setDX(int dx) {
    this.dx = dx;
  }
  public void setDY(int dy) {
    this.dy = dy;
  }
  
}

/********************************************************
*
*  GameListener
*  
*  reacts to user input in startup menu
*
********************************************************/
class GameListener implements ActionListener {
  
  private JList<String> gameList;
  private JTextField nameField;
  private DistGame game;
  
  public GameListener(JList<String> list, JTextField nameField, DistGame game) {
    
    this.gameList = list;
    this.nameField = nameField;
    this.game = game;
    
  }

  @Override
  public void actionPerformed(ActionEvent e) {

    // start a new game
    if ("newGame".equals(e.getActionCommand())) {
      String name = nameField.getText();
      Random rand = new Random(name.hashCode());
      String genAddr = "226.0.";
      genAddr = genAddr + Integer.toString(rand.nextInt(201));
      genAddr = genAddr + ".";
      genAddr = genAddr + Integer.toString(rand.nextInt(201));
      try {
        InetAddress addr = InetAddress.getByName(genAddr);
        game.createGame(addr);
        game.createHostThread(addr, name);
      } catch (UnknownHostException ex) {
        ex.printStackTrace();
      }
    
    // join an existing game
    } else if ("joinGame".equals(e.getActionCommand())) {
      String targetGame = gameList.getSelectedValue();
      Random rand = new Random(targetGame.hashCode());
      String genAddr = "226.0.";
      genAddr = genAddr + Integer.toString(rand.nextInt(201));
      genAddr = genAddr + ".";
      genAddr = genAddr + Integer.toString(rand.nextInt(201));
      try {
        InetAddress addr = InetAddress.getByName(genAddr);
        game.createGame(addr);
      } catch (UnknownHostException ex) {
        ex.printStackTrace();
      }
    }
    
  }
  
}

/******************************************************
 * 
 *  GamePanel
 *  
 *  extended JPanel with custom paintComponent()
 *  to animate the game
 * 
 ******************************************************/
class GamePanel extends JPanel {

  private static final long serialVersionUID = 8447941726684259648L;
  private Gamestate gamestate;
  private int height, width;
  private InputMap im;
  private ActionMap am;
  
  public GamePanel(int height, int width, Gamestate gamestate) {
    
    this.height = height;
    this.width = width;
    this.gamestate = gamestate;
    
    // set up actionmap to respond to key presses
    im = getInputMap(WHEN_IN_FOCUSED_WINDOW);
    am = getActionMap();
    im.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT,  0, false), "leftPressed");
    im.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT,  0, false), "rightPressed");
    im.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE,  0, false), "spacePressed");
    im.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT,  0, true), "leftReleased");
    im.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT,  0, true), "rightReleased");
    am.put("leftPressed", new AbstractAction() {
      private static final long serialVersionUID = 1L;
      @Override
      public void actionPerformed(ActionEvent e) {
        gamestate.moveChar(0);
      }
    });
    am.put("rightPressed", new AbstractAction() {
      private static final long serialVersionUID = 1L;
      @Override
      public void actionPerformed(ActionEvent e) {
        gamestate.moveChar(1);
      }
    });
    am.put("spacePressed", new AbstractAction() {
      private static final long serialVersionUID = 1L;
      @Override
      public void actionPerformed(ActionEvent e) {
        gamestate.moveChar(2);
      }
    });
    am.put("leftReleased", new AbstractAction() {
      private static final long serialVersionUID = 1L;
      @Override
      public void actionPerformed(ActionEvent e) {
        gamestate.moveChar(3);
      }
    });
    am.put("rightReleased", new AbstractAction() {
      private static final long serialVersionUID = 1L;
      @Override
      public void actionPerformed(ActionEvent e) {
        gamestate.moveChar(4);
      }
    });
    
  }
  
  public Dimension getPreferredSize() {
    return new Dimension(width, height);
  }
  
  public void paintComponent(Graphics g) {
    
    super.paintComponent(g);
    
    for (Character ch : gamestate.getCharList()) {
      synchronized (ch) {
        g.setColor(ch.color);
        g.fillRect((int) ch.x, (int) ch.y, 60, 80);
        g.setColor(Color.BLACK);
        g.drawRect((int) ch.x, (int) ch.y, 60, 80);
      }
    }
    
  }
  
}  // GamePanel