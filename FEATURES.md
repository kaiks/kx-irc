# IRC ZNC Client Scenarios

## Feature: ZNC connection requirements

Scenario: Connect can proceed without a password
  Given I enter a host and port
  And I leave the password empty
  When I tap Connect
  Then the UI should enter Connecting
  And it should not immediately show Connected

Scenario: Connect only after server welcome
  Given I enter valid ZNC credentials and tap Connect
  When the server TCP socket opens but no welcome (001) is received
  Then the UI should stay in Connecting
  And it should not show Connected

Scenario: Connect and auto-join configured channels
  Given I enter valid ZNC credentials and channels "#one #two"
  When the server sends welcome (001)
  Then the client should send JOIN for "#one" and "#two"
  And the UI should show Connected

## Feature: Multi-channel usage and switching

Scenario: See and switch between multiple channels
  Given I configured channels "#one #two"
  And the server sends messages in #one and #two
  When I select "#one" in the channel list
  Then I should only see messages from #one
  And switching to "#two" shows only #two messages

Scenario: Direct messages appear as their own target
  Given a user "alice" sends me a direct message
  When the message arrives
  Then "alice" should appear in the target list
  And selecting "alice" shows only messages with alice

Scenario: All-target view shows every message
  Given I have messages across multiple targets
  When I select "All"
  Then I should see messages from every target

## Feature: Form usability and scrolling

Scenario: Settings area is scrollable on small screens
  Given the form is taller than the visible screen
  When I scroll the settings area
  Then I can reach the ZNC password fields

Scenario: Single-line inputs do not insert newlines
  Given I focus on a text input in the settings
  When I press the Enter action
  Then a newline is not inserted
  And focus advances or the keyboard closes

Scenario: Message send uses keyboard action
  Given I typed a message in the composer
  When I press the Send action on the keyboard
  Then the message is sent
  And the input is cleared

## Feature: IRC formatting

Scenario: IRC color and style codes render without control characters
  Given a message contains IRC style control codes
  When it is displayed
  Then the message text is shown without raw control characters
