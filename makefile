JFLAGS = -g
JC = javac

all: DistGame

DistGame: DistGame.class

DistGame.class: DistGame.java
	$(JC) $(JFLAGS) DistGame.java

clean:
	$(RM) *.class
