NUWA Robot SDK
ver 1.1.4
How to start
System Requirement
How to use
Nuwa Developer MODE
Change your adb password
How to install APK
Initiation
Register callback
Check Nuwa Robot SDK engine is ready before app calling any Robot SDK API
Feature list:
Nuwa Motion player
Overlap Face Window control
Voice wakeup
Local TTS
Local ASR
Cloud Speech To Text
Sensor event
LED control
Motor control
Movement control
Face control
Custom Behavior
Safe mode
Handle Robot Drop event
Handle Robot Service recovery
How to shown entrypoint on Robot Menu
Launch Developer App via Voice command
Disable Support Always Wakeup
Setup 3rd App as Launcher app to replace Nuwa Face Activity
How to change your adb password
Prevent HOME KEY to suspend device
How to Auto Launch 3rd Activity when bootup
Q & A
System Requirement
Kebbi-air is based on Android P. You can use Android Studio or other IDE for android developing. Please refer to Android Studio for more information.

How to use
Get Nuwa SDK aar and import into your android studio project / or unity IDE.

Nuwa Robot SDK supports the following development environment.

Android Studio
Unity for Android
Developer could refer to Nuwarobotics official developer website or GitHub to understand the API usage.

[NUWA Robot SDK Manual] NUWA Developer
[NUWA Robot SDK aar] Download from NUWA Developer
[NUWA Robot SDK JavaDoc] NUWA Developer
[NUWA Robot SDK Example for Android] NUWA Github
[Kebbi Motion list] NUWA MOTION
[Kebbi Content Editor] NUWA Content Editor
NOTICE : Content Editor only for Business Partner, please contact nuwarobotics for more information.

Nuwa Developer MODE
How to enable developer mode

1. Open Settings 
2. click "About Kebbi" 10 times
3. The hidden developer option shown in right page.
How to hide developer option

1. Open Settings 
2. click "About Kebbi" 10 times
3. All developer option will hidden.
Change your adb password
Allow Developer change adb password to prevent other user access android system.

1. Open Setting
2. click "About Kebbi" 10 times to enable developer menu
3. Click "ADB Password Settings"   (ADBパスワード設定)
4. Replace default password to your own.
developer_adb_change_password

How to install APK
User can use ADB(android debug bridge) directly to install apk. (in Kebbi-Air)

The ADB password is !Q@W#E$R
After adb connected, developer can use adb install command to install apk.

Initiation
Before using Robot SDK, App needs to to create a single instance of Nuwa Robot API and do initiation once first.

    // ID name: please naming your own client id, 
    // Once mRobot is created, app will receive a callback of onWikiServiceStart()
    IClientId id = new IClientId("your_app_package_name");
    NuwaRobotAPI mRobot = new NuwaRobotAPI(android.content.Context, id);

    // release Nuwa SDK resource while App closed.(or Activity in pause/destroy state)
    mRobot.release();
Register callback
Due to all functions of Nuwa Robot SDK are async(Based on AIDL) design, App needs to register a callback to receive all kinds of notifications and results from Robot.

RobotEventListener
VoiceEventListener

 mRobot.registerRobotEventListener(new RobotEventListener() {
     @Override
     public void onWikiServiceStart() {
         // Once mRobot is created, you will receive a callback of onWikiServiceStart()
     }
     @Override
     public void onTouchEvent(int type, int touch) {

     }
     .......
 });

 mRobot.registerVoiceEventListener(new VoiceEventListener() {
     @Override
     public void onTTSComplete(boolean isError) {
         // TODO Auto-generated method stub

     }
     .......
 });
You could also use helper class to overwrite events that App needs only.

RobotEventCallback
VoiceEventCallback

 mRobot.registerRobotEventListener(new RobotEventCallback() {
     @Override
     public void onTouchEvent(int type, int touch) {

     }
 });

 mRobot.registerVoiceEventListener(new VoiceEventCallback() {
     @Override
     public void onTTSComplete(boolean isError) {

     }
 });
Check Nuwa Robot SDK engine is ready before app calling any Robot SDK API
Once mRobot is created, you will receive a callback of onWikiServiceStart()

NOTICE : please call api after onWikiServiceStart.

    mRobot.registerRobotEventListener(new RobotEventListener() {
        @Override
        public void onWikiServiceStart() {
            // Once mRobot is created, you will receive a callback of onWikiServiceStart()
        }
        .......
    });

    // or check state by
    boolean isReady = mRobot.isKiWiServiceReady();
Nuwa Motion player
Nuwa motion file is nuwa's private Robot motion control format. Which is composed of "MP4(Face)", "Motor control", "Timeline control", "LED control", etc. You can just play a motion, and Robot will do a serials of pre-defined actions. PS: Some motions only include "body movements" without "MP4(Face)"

motionPlay(final String motion, final boolean auto_fadein)
motion: motion name, ex: 666_xxxx
auto_fadein: Screen will show a Surface Window for motion include Face, the param of "auto_fadein" needs to set as "true"
motionPlay(final String name, final boolean auto_fadein, final String path)
motionPrepare(final String name)
motionPlay( )
motionStop(final boolean auto_fadeout)
auto_fadeout: stop playing a motion and hide the overlap window as well
getMotionList( )
motionSeek(final float time)
motionPause( )
motionResume( )
motionCurrentPosition( )
motionTotalDuration( )
    /*
        will get callbacks of onStartOfMotionPlay onStartOfMotionPlay(String motion)
        while finish plaing, will get callbacks of 
        onStopOfMotionPlay(String motion)
        onCompleteOfMotionPlay(String motion)

        // if there is an error, App will receive the error callback
        public void onErrorOfMotionPlay(int errorcode);
    */
    // use default NUWA motion asset path
    mRobot.motionPlay("001_J1_Good", true/false);

    // give a specefic motion asset path (internal use only)
    mRobot.motionPlay("001_J1_Good", true/false, "/sdcard/download/001/");
    //NOTICE:Please must call motionStop(true), if your auto_fadein is true.

    // will get callback of onStopOfMotionPlay(String motion)
    mRobot.stop(true/false);

    // preload motion, and then playback
    // callback to onPrepareMotion(boolean isError, String motion, float duration)
    mRobot.motionPrepare("001_J1_Good")

    // pause, resume and seek
    mRobot.motionPause();
    float pos = mRobot.motionCurrentPosition();
    mRobot.motionResume();
    mRobot.motionSeek(1.5f); // seek to 1.5 sec
Kebbi Motion list

Overlap window control
While playing a motion, there is a overlap window on the top of screen, you can do show or hide function with the window.

showWindow(final boolean anim)
hideWindow(final boolean anim)

 mRobot.showWindow(false);
 mRobot.hideWindow(false);
Voice wakeup
You can say "Hello Kebbi" to get a wakeup event callback in your Appication context ONCE, and robot system behavior NOT response on this time.

startWakeUp(final boolean async)

stopListen( )


 mRobot.startWakeUp(true);

 // When Robot hear "Hello Kebbi"，Kiwi agent will receive a callback onWakeup() of VoiceEventListener once
 onWakeup(boolean isError, String score) {
     // score: a json string given by voice engine
     // isError: if engine works normally
     // Ex: {"eos":3870,"score":80,"bos":3270,"sst":"wakeup","id":0}
     // score: confidence value
 }

 // stop listen to wakeup
 mRobot.stopListen();
Local TTS
Robot can speak out a sentance by a giving string

startTTS(final String tts)
startTTS(final String tts, final String locale)
pauseTTS()
resumeTTS()
stopTTS()

 mRobot.startTTS("Nice to meet you");

 // you can cancel speaking at any time
 mRobot.stopTTS();
 // or Used specific language speak (Please reference TTS Capability for market difference)
 // mRobotAPI.startTTS(TTS_sample, Locale.ENGLISH.toString());

 // receive callback onTTSComplete(boolean isError) of VoiceEventListener
 onTTSComplete(boolean isError) {
 }
TTS Capability (Only support on Kebbi Air)

* Taiwan Market : Locale.CHINESE\Locale.ENGLISH
* Chinese Market : Locale.CHINESE\Locale.ENGLISH
* Japan Market : Locale.JAPANESE\Locale.CHINESE\Locale.ENGLISH
* Worldwide Market : Locale.ENGLISH
Local ASR
ASR engine use SimpleGrammarData to describe the list of keyword, App needs to create Grammar first. (Command table)

createGrammar(final String grammar_id, final String grammar_string)
startLocalCommand()
stopListen()
Example

    //list listen syntax
    ArrayList<String> cmd = new ArrayList<>();
    cmd.add("I want to listen music");
    cmd.add("play pop music");
    cmd.add("listen love song");
    //Make Grammar Object by SimpleGrammarData class
    SimpleGrammarData mGrammarData = new SimpleGrammarData("TutorialTest");
    for (String string : cmd) {
      mGrammarData.addSlot(string);
    }
    //update config
    mGrammarData.updateBody();
    //Regist local ASR syntax
    mRobot.createGrammar(mGrammarData.grammar, mGrammarData.body);

    // stop ASR operation
    mRobot.stopListen();
VoiceEventListener Callback

    // receive a GrammarState callback of VoiceEventListener
    public void onGrammarState(boolean b, String s) {
      // now you can call startLocalCommand() API
      mRobot.startLocalCommand();
    }

    // receive a Understand callback of VoiceEventListener
    public void onMixUnderstandComplete(boolean b, ResultType resultType, String s) {
      //Get ASR result string here
      String result_string = VoiceResultJsonParser.parseVoiceResult(s);

    }
Cloud Speech To Text and Local ASR mix operation
While doing ASR Mix mode, engine will receive results from local and cloud, engine will only return one of both. Rules are: </br>

if engine get local result, it will only return local result
if engine get no local result, it will return ASR result if Internet is available
PS: Cloud ASR and local ASR result are json format string.

createGrammar(final String grammar_id, final String grammar_string)
startMixUnderstand( )
The local result is returned first. If there is no local result, the cloud NLP result will be returned. The grammar table must be built before use the api
stopListen( )
Example

    //list listen syntax
    ArrayList<String> cmd = new ArrayList<>();
    cmd.add("I want to listen music");
    cmd.add("play pop music");
    cmd.add("listen love song");
    //Make Grammar Object by SimpleGrammarData class
    SimpleGrammarData mGrammarData = new SimpleGrammarData("TutorialTest");
    for (String string : cmd) {
      mGrammarData.addSlot(string);
    }
    //update config
    mGrammarData.updateBody();
    //Regist local ASR syntax
    mRobot.createGrammar(mGrammarData.grammar, mGrammarData.body);


    // stop ASR operation
    mRobot.stopListen();
VoiceEventListener Callback

    // receive a callback of VoiceEventListener
    onGrammarState(boolean isError, String info) {
    // now you can call startMixUnderstand() API
    // do mix mode ASR
      mRobot.startMixUnderstand();
    }

    // get local
    onMixUnderstandComplete(boolean isError, ResultType type, String json) {
      //Get ASR result string
      String result_string = VoiceResultJsonParser.parseVoiceResult(json);
      //get ASR type
      if (type == ResultType.LOCAL_COMMAND) {
        //do something
      }
    }
Sensor event
Robot provides Touch and PIR and Dropsensor events. While you need it, you can request it. While you don't need it, you can stop requesting it.

requestSensor(final int op)
stopSensor(final int op)

 public static final int SENSOR_NONE = 0x00;             //000000
 public static final int SENSOR_TOUCH = 0x01;            //000001
 public static final int SENSOR_PIR = 0x02;              //000010
 public static final int SENSOR_DROP = 0x04;             //000100
 public static final int SENSOR_SYSTEM_ERROR = 0x08;     //001000

 // request touch sensor event
 mRobot.requestSensor(SENSOR_TOUCH);

 // or request touch, PIR event and SENSOR_XXX
 mRobot.requestSensor(NuwaRobotAPI.SENSOR_TOUCH | NuwaRobotAPI.ENSOR_PIR | NuwaRobotAPI.SENSOR_XXX);

 // get raw touch results of RobotEventListener
 // type: head: 1, chest: 2, right hand: 3, left hand: 4, left face: 5,right face: 6.
 // touch: touched: 1, untouched: 0
 onTouchEvent(int type, int touch) {
 }

 // type: head: 1, chest: 2, right hand: 3, left hand: 4, left face: 5,right face: 6.
 onTap(int type) {
 }

 // type: head: 1, chest: 2, right hand: 3, left hand: 4, left face: 5,right face: 6.
 onLongPress(int type) {
 }

 // get PIR results of RobotEventListener
 onPIREvent(int val) {
 }

 // stop all requested sensor event
 mRobot.stopSensor(NuwaRobotAPI.SENSOR_NONE);

 // stop multiple sensor events
 mRobot.stopSensor(NuwaRobotAPI.SENSOR_TOUCH | NuwaRobotAPI.SENSOR_XXX);
LED control
There are 4 parts of LED on Robot. API can contorl each them. (Head, Chest, Right hand, Left hand) Each LED part has 2 types of modles - "Breath mode" and "Light on mode" Before using it, you need to use API to turn on it first, and turn off it while unneeded. LED default controled by System. If the App wants to have different behavior, it can be disabled by disableSystemLED()
If your App needs to control Robot LED, App needs to call disableSystemLED() once，and App call enableSystemLED() while App is in onPause state.

NOTICE : Kebbi-air not support FACE and Chest LED Breath mode.

enableLed(final int id, final int onOff)
setLedColor(final int id, final int brightness, final int r, final int g, final int b)
enableLedBreath(final int id, final int interval, final int ratio)
enableSystemLED() // LED be controlled by Robot itself
disableSystemLED() // LED be controlled by App

 /*
 id:1 = Face LED
 id:2 = Chest LED
 id:3 = Left hand LED
 id:4 = Right hand LED
 onOff:  0 or 1
 brightness, Color-R, Color-G, Color-B: 0 ~ 255
 interval: 0 ~ 15 
 ratio: 0 ~ 15
 */

 // turn on LED
 mRobot.enableLed(1, 1);
 mRobot.enableLed(2, 1);
 mRobot.enableLed(3, 1);
 mRobot.enableLed(4, 1);

 // Set LED color
 mRobot.setLedColor(1, 255, 255, 255, 255);
 mRobot.setLedColor(2, 255, 255, 0, 0);
 mRobot.setLedColor(3, 255, 166, 255, 5);
 mRobot.setLedColor(4, 255, 66, 66, 66);

 // Switch to "Breath mode"
 mRobot.enableLedBreath(1, 2, 9);

 // turn off LED
 mRobot.enableLed(1, 0);
 mRobot.enableLed(2, 0);
 mRobot.enableLed(3, 0);
 mRobot.enableLed(4, 0);
Motor control
Mibo Robot has 10 motors, use the API, you can control each of them

ctlMotor(final int motorid, final int speed, final float setPositionInDegree, final float setSpeedInDegreePerSec)
motorid:
neck_y:1
neck_z:2
right_shoulder_z:3
right_shoulder_y:4
right_shoulder_x:5
right_bow_y:6
left_shoulder_z:7
left_shoulder_y:8
left_shoulder_x:9
left_bow_y:10
speed: always 0
setPositionInDegree: target positon in degree
setSpeedInDegreePerSec: speed in degree per sec (range: 0 ~ 200)
robot_motor_map

Motor angle range table
| ID | 1  | 2  | 3  | 4  | 5  | 6  | 7  | 8  | 9  | 10 |
|---|----|----|----|----|----|----|----|----|---|----|
|Max| 20 | 40 | 5  | 70 | 100| 0  | 5  | 70 |100| 0  |
|Min|-20 |-40 | -85|-200| -3 |-80 |-85 |-200|-3 |-80 |
  // control neck_y motor to 20 degree in 40 Degree/sec speed 
  mRobot.ctlMotor(1, 0, 20, 40f);
Movement control
To control Robot to forward, backwards, turns, stop

Low level control:
move(float val) max: 0.2(Meter/sec) go forward, min: -0.2(Meter/sec) go back
turn(float val) max: 30(Degree/sec) turn left, min: -30(Degree/sec) turn right
Advanced control:
forwardInAccelerationEx()
backInAccelerationEx()
stopInAccelerationEx()
turnLeftEx()
turnRightEx()
stopTurnEx()
// go forward
mRobot.forwardInAccelerationEx();
// go back
mRobot.backInAccelerationEx();
// stop
mRobot.stopInAccelerationEx();
Face control
Target SDK : 2.0.0.05 The robot face is constructed with the Unity engine. The face control mechanism receives instructions via android service AIDL. It will init and binding service when constructing the new NuwaRobotAPI object, use UnityFaceManager object to call face control instruction.
IMPORTANT NOTICE :

The Robot Face is a Activity, so the controller must be SERVICE on background.

Face control API:
mouthOn(long speed)
mouthOff()
hideUnity
showUnity()
playMotion(String json,IonCompleteListener listener)
registerCallback(UnityFaceCallback callback)
Example

  ---------------------------------------------------------------------------
   // get control object 
   UnityFaceManager mFaceManager = UnityFaceManager.getInstance();

  ---------------------------------------------------------------------------
   /** 
    * open face mouth method 
    * @param speed : Mouth animation speed
   */
   long speak = 300l;
   mFaceManager.mouthOn(speed);

  ---------------------------------------------------------------------------
   /** 
    * close face mouth method 
   */
   mFaceManager.mouthOff();

  ---------------------------------------------------------------------------
   /** 
    * hide face method 
   */
   mFaceManager.hideUnity();

  ---------------------------------------------------------------------------
   /** 
    * show face method 
   */
   mFaceManager.showUnity();

  ---------------------------------------------------------------------------
   /** 
    * play unity face motion method
    * @param json : unity face motion key
    * @param mListener : callback for motion complete
   */
   String json = "J2_Hug";
   IonCompleteListener.Stub mListener = new IonCompleteListener.Stub() {
        @Override
        public void onComplete(String s) throws RemoteException {
            Log.d("FaceControl", "onMotionComplete:" + s );
        }
    };
   mFaceManager.playMotion(json,mListener);

  ---------------------------------------------------------------------------
   /** 
    * regist the callback that face status on touch 
    * @param UnityFaceCallback : over write callback receive method
   */
   mController.registerCallback(new UnityFaceCallback());
   class UnityFaceCallback extends UnityFaceCallback {
        @Override
        public void on_touch_left_eye() {
            Log.d("FaceControl", "on_touch_left_eye()");
        }

        @Override
        public void on_touch_right_eye() {
            Log.d("FaceControl", "on_touch_right_eye()");
        }

        @Override
        public void on_touch_nose() {
            Log.d("FaceControl", "on_touch_nose()");
        }

        @Override
        public void on_touch_mouth() {
            Log.d("FaceControl", "on_touch_mouth()");
        }

        @Override
        public void on_touch_head() {
            Log.d("FaceControl", "on_touch_head()");
        }

        @Override
        public void on_touch_left_edge() {
            Log.d("FaceControl", "on_touch_left_edge()");
        }

        @Override
        public void on_touch_right_edge() {
           Log.d("FaceControl", "on_touch_right_edge()");
        }

        @Override
        public void on_touch_bottom() {
           Log.d("FaceControl", "on_touch_bottom()");
        }
    }

  ---------------------------------------------------------------------------
Custom Behavior
Target SDK : 2.0.0.05
Nuwa Custom Behavior Example

IMPORTANT :

NUWA Robot NLP only support on Robot Face Activity and Nuwa Launcher Menu, please make sure no 3rd customize activity cover on foreground.
System behavior allows developer to customize response behavior of a NLP result. Developer need to setup Chatbot Q&A from NUWA Trainingkit website which allow developer setup Custom Intention for a sentence. Following sample code will present how to register receive this CustomIntentation notify and implement customize response.

class BaseBehaviorService
Developer should implement a class to determine how to react when receiving a customized NLP response from NUWA Trainingkit.

Nuwa Trainingkit Website
This could be achieved by extending from class BaseBehaviorService.

BaseBehaviorService declared three important functions which onInitialize(), createCustomBehavior() and notifyBehaviorFinished().

There are two functions need to be implemented which onInitialize() and createCustomBehavior().

onInitialize()
When the extending class has been started, developer could initialize resource here.
createCustomBehavior()
After that, the other function createCustomBehavior should create a CustomBehavior object. CustomBehavior is a interface which define custom behavior once receiving customized NLP result. It declared three callback functions for developer to implement customized behavior.
notifyBehaviorFinished()
To notify robot behavior system that the process has been completed.
class ISystemBehaviorManager
This class has been encapsulated in BaseBehaviorService. Developer can get this instance which is created by BaseBehaviorService.

register(String pkgName, CustomBehavior action)
Register a CustomBehavior object to robot behavior system by app package name.
unregister(String pkgName, CustomBehavior action)
Unregister a CustomBehavior object to robot behavior system by app package name.
setWelcomeSentence(String[] sentences)
To set welcome sentence when robot detected someone.
resetWelcomeSentence()

To reset welcome sentence.
completeCustomBehavior()

To notify robot behavior system that the process has been completed.
class CustomBehavior
The Object which let developer define how to to deal with customized NLP response.

prepare(String parameter)
Once NLP response which is defined form TrainingKit has been reached, the robot behavior system will start to prepare resource for this session. The system notifies 3rd party APP and it could do something while preparing.
process(String parameter)
Developer could implement his logic here to deal with NLP response which has been defined from TrainingKit. As robot behavior system is ready, this function call will be invoked. Note that the process might be an asynchronous task so that the developer must notify robot behavior system that the process when will be completed by notifyBehaviorFinished().
finish(String parameter)
When this customized behavior has been completed, robot behavior system will clean related resources and finish this session. At the same time, it notifies the 3rd party app this session has been finished by this function call.
Sample code
public class CustomBehaviorImpl extends BaseBehaviorService {

    @Override
    public void onInitialize() {
        handler = new Handler(Looper.getMainLooper());
        try {
            // TODO initialize 
            mSystemBehaviorManager.setWelcomeSentence(new String[]{"你好， %s.這是一個歡迎詞的測試!", "%s, 挖底家", "%s, 有什麼可以為您服務的嗎？"});
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    @Override
    public CustomBehavior createCustomBehavior() {
        return new CustomBehavior.Stub() {
            @Override
            public void prepare(final String parameter) {
                // TODO write your preparing work
            }

            @Override
            public void process(final String parameter) {
                // TODO the actual process logic

                // TODO simulate asynchronous task while process complete
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            notifyBehaviorFinished();
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    }
                }, 5000);
            }

            @Override
            public void finish(final String parameter) {
                // TODO the whole session has been finished. 
            }
        };
    }

}
Safe mode
Make Robot could stand in place at any case. Robot will auto enable "lock wheel mode" while AC is plugged. App could call "unlockWheel()" to disable the safe mode.

lockWheel()
unlockWheel()
Handle Robot Drop event
When Robot drop happening, it will deliver error message and App could receive the error message via RobotEventListener callback

getDropSensorOfNumber()
    // request Robot drop event first
    mRobot.requestSensor(NuwaRobotAPI.SENSOR_DROP);

    // handle it 
    public void onDropSensorEvent(int value) {
        // value: 1 : drop
                  0 : normal

        // get the amount of drop IR sensor 
        int val = mRobot.getDropSensorOfNumber();
    }

    // release sensor event
    mRobot.stopSensor(NuwaRobotAPI.SENSOR_NONE);
Handle Robot Service Recovery
When Robot service(Robot SDK) happens unexpected exception, it will restart automaticaly. App could handle the scenario by following callback of RobotEventListener.

    public void onWikiServiceStart() {
        //1. Robot SDK is ready to use now.
        //2. Robot SDK restart successfully, and it's ready to use now.
        Log.d(TAG, "onWikiServiceStart");
    }

    @Override
    public void onWikiServiceCrash() {
        // When Robot service(Robot SDK) happens unexpected exception, it will shutdown itself.
        Log.d(TAG, "onWikiServiceCrash");
    }

    @Override
    public void onWikiServiceRecovery() {
        // Robot service(Robot SDK) begins to restart itself.
        // When it's ready, it will send "onWikiServiceStart" event to App again.
        Log.d(TAG, "onWikiServiceRecovery");
    }
How to shown entrypoint on Robot Menu
Robot-air menu base on Launcher, please implement standard launcher icon. AndroidManifest.xml

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/AppTheme">
        <activity
            android:name=".MainActivity"
            android:label="@string/app_name"
            android:theme="@style/AppTheme.NoActionBar">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
Android Developer reference link https://developer.android.com/guide/topics/manifest/manifest-intro#iconlabel

Launch Developer App via Voice command
In Original Android design, App is launched by tapping icon in Launcher. In NUWA Robot we allow use "Voice command" to launch an activity or broadcast an intent to a registered receiver.

*Developer can setup several "Voice Command" separate by ",".

NOTICE: "Voice Command" have to be 100% matched.

    Ex: in your manifest, add the "intent-filter" of "com.nuwarobotics.api.action.VOICE_COMMAND" in "activity" to identify the activity wants to be launched by Robot Launcher while Robot hears voice commands like [Play competition game,Play student competition game,I want play student game,student competition].

    <activity
        android:name=".YouAppClass"
        android:exported="true"
        android:label="App Name"
        android:screenOrientation="landscape" >

        <intent-filter>
            <action android:name="com.nuwarobotics.api.action.VOICE_COMMAND" />
        </intent-filter>

        <meta-data
            android:name="com.nuwarobotics.api.action.VOICE_COMMAND"
            android:value="Play competition game,Play student competition game,I want play student game,student competition" />
    </activity>

    Ex: in your manifest, add the "intent-filter" of "com.nuwarobotics.api.action.VOICE_COMMAND" in "receiver" to identify the receiver wants to be launched by Robot Launcher while Robot hears voice commands like [I want to watch TV,I want to sleep,I want to turn on air conditioner].
    <receiver android:name=".VoiceCommandListener" >
        <intent-filter>
            <action android:name="com.nuwarobotics.api.action.VOICE_COMMAND" />
        </intent-filter>

        <meta-data
            android:name="com.nuwarobotics.api.action.VOICE_COMMAND"
            android:value="I want to watch TV,I want to sleep,I want to turn on air conditioner" />
    </receiver> 

    // in VoiceCommandListener.java
    public class VoiceCommandListener extends BroadcastReceiver{
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "action:" + intent.getAction());
            if (intent.getAction().equals("com.nuwarobotics.api.action.VOICE_COMMAND")) {
                String cmd = intent.getStringExtra("cmd");
                Log.d(TAG, "user speak: " + cmd);
            }
        }
    }
Disable Support Always Wakeup
Kebbi-air support "Always wakeup" anywhere. (Kebbi and Danny only support wakeup on Face) We allow App declare on Application or Activity scope.

Declare on AndroidManifest.xml
 <meta-data android:name="disableAlwaysWakeup" android:value="true" />
Application Scope Example

    <application
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:theme="@style/AppTheme">

        <meta-data android:name="disableAlwaysWakeup" android:value="true" />

    </application>
Activity Scope Example

    <activity
        android:name=".YouAppClass"
        android:exported="true"
        android:label="App Name"
        android:screenOrientation="landscape" >

        <meta-data android:name="disableAlwaysWakeup" android:value="true" />

    </activity>
Setup 3rd App as Launcher app to replace Nuwa Face Activity
Nuwa Face Activity is a Unity Activity to present kebbi face. We setup it as default HOME APP to make sure HOME Key can back to Nuwa Face Activity.

We allow Developer totally replace kebbi face by following implementation.

Step1 : declare 3rd App as a HOME APP.

  <intent-filter>
      <action android:name="android.intent.action.MAIN" />
      <category android:name="android.intent.category.HOME" />
      <category android:name="android.intent.category.DEFAULT" />
      <category android:name="android.intent.category.LAUNCHER" />
  </intent-filter>
Step 2 : Setup 3rd App as default HOME App.

1. Open Setting
2. click "About Kebbi" 10 times to enable developer menu
3. click "Home Setting"(啟動器設定) 
setting

4. "Home app" (ホームアプリ) -> setup to your app
setting_default_app setting_default_launcher

NOTICE : This setting will cause "Nuwa Face Activity" not always shown after HOME KEY pressed. You have to call Face Control API to trigger shown.

Prevent HOME KEY to suspend device
Some business environment request not allow user suspend device by press HOME KEY.

1. Open Setting
2. click "About Kebbi" 10 times to enable developer menu    1. 
3. "Home screen key for locking screen" (ホーム画面のキーロック画面)
4. disable it to prevent goToSleep when press HOME Key on Nuwa Face Activity.
How to Auto Launch 3rd Activity when bootup
3rd App can declare following intent on Androidmanifest.xml.

Full Example：

<activity android:name=".CustomerAutoStartActivity">
    <intent-filter>
       <action android:name="com.nuwarobotics.feature.autolaunch" />
       <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
Q & A
Q1: Why it dodn't work while App calls "mRobot.motionPlay("001_J1_Good", true/false)"?
Ans:

check the callback of "public void onErrorOfMotionPlay(int errorcode) to know the error info"
make sure the motion name is valid
check the motion list to get the correct motion name
[Kebbi Motion list] https://dss.nuwarobotics.com/documents/listMotionFile

Q2: Why App can not control LED?
Ans:

App needs to call disableSystemLED() once first. Ex: Do it in onCreate() state.
App needs to call enableSystemLED() while App closed. Ex: Do it in onPause() state.
Q3: Why App GUI widget(Ex: Button) can not receive any touch event?
Ans:

There is an overlap window on your app, App call "hideWindow(false)" to close the window.
Q4: Why App GUI widget(Ex: button) can not receive any touch event after playing a motion?
Ans:

There is an overlap window on your app, App call "hideWindow(false)" to close the window in the callback of onCompleteOfMotionPlay().
Q5: Does local ASR and cloud ASR support English?
Ans:

Support "Japanese" and "English".
Q6: Does local TTS support English?
Ans:

TTS supports "Japanese" and "English"
Q7: Why App can not receive Robot touch event?
Ans:

App calls "mRobot.requestSensor(SENSOR_TOUCH)" once.
Handle the following callback
    mRobot.registerRobotEventListener(new RobotEventListener() {
        @Override
        public void onTouchEvent(int type, int touch) {
            // type: head: 1, chest: 2, right hand: 3, left hand: 4, left face: 5,right face: 6.
            // touch: touched: 1, untouched: 0
        }
    });
App calls "mRobot.stopSensor(NuwaRobotAPI.SENSOR_NONE)" while App doesn't want to receive the touch events anymore.