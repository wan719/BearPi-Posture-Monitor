/*
 * Posture Monitor V4.3
 *
 * V3:
 *   Arm / Disarm
 *   NORMAL / TILT / MOVING / IMPACT
 *
 * V4:
 *   Wi-Fi STA
 *   MQTT
 *   JSON telemetry
 */

#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include "ohos_init.h"
#include "cmsis_os2.h"

#include "E53_SC2.h"
#include "wifi_connect.h"
#include "wifi_config.h"

#include "wifiiot_gpio.h"
#include "wifiiot_gpio_ex.h"

#include "MQTTClient.h"


#define TASK_STACK_SIZE 1024 * 10
#define TASK_PRIO 25


/* ---------- Posture thresholds ---------- */

#define NORMAL_BASE_THRESHOLD 150
#define TILT_BASE_THRESHOLD   300

#define MOVING_THRESHOLD      150
#define IMPACT_THRESHOLD      1500

/*
 * Approximately:
 *
 * E53 read delay 50 ms
 * +
 * task delay 150 ms
 *
 * ~= 200 ms / sample
 *
 * 10 samples ~= 2 seconds
 */
#define MQTT_HEARTBEAT_SAMPLES 10


/* MQTT topic */

#define MQTT_STATUS_TOPIC "bearpi/posture/status"


E53_SC2_Data_TypeDef E53_SC2_Data;


/* =========================================================
 * Posture state
 * =========================================================
 */

typedef enum
{
    STATE_NORMAL = 0,
    STATE_TILT,
    STATE_MOVING,
    STATE_IMPACT
} MonitorState;


/* =========================================================
 * System state
 * =========================================================
 */

static int g_armed = 0;

static volatile int g_armToggleRequested = 0;


/* Baseline */

static int g_baseX = 0;
static int g_baseY = 0;
static int g_baseZ = 0;


/* Previous sample */

static int g_prevX = 0;
static int g_prevY = 0;
static int g_prevZ = 0;


static unsigned int g_sampleCount = 0;
static unsigned int g_heartbeatCounter = 0;


static MonitorState g_stableState = STATE_NORMAL;
static MonitorState g_lastState = STATE_NORMAL;


/* =========================================================
 * MQTT
 * =========================================================
 */

static Network g_network;
static MQTTClient g_mqttClient;

static unsigned char g_mqttSendBuf[512];
static unsigned char g_mqttReadBuf[512];

static int g_mqttReady = 0;


/* =========================================================
 * Utility
 * =========================================================
 */

static int AbsInt(int value)
{
    return value >= 0 ? value : -value;
}


static const char *GetStateName(MonitorState state)
{
    switch (state)
    {
        case STATE_NORMAL:
            return "NORMAL";

        case STATE_TILT:
            return "TILT";

        case STATE_MOVING:
            return "MOVING";

        case STATE_IMPACT:
            return "IMPACT";

        default:
            return "UNKNOWN";
    }
}


/* =========================================================
 * MQTT connection
 * =========================================================
 */

static int MQTTConnectBroker(void)
{
    int rc;

    MQTTString clientId = MQTTString_initializer;

    MQTTPacket_connectData data =
        MQTTPacket_connectData_initializer;


    printf("\r\n");
    printf("============================================\r\n");
    printf("[MQTT] Connecting to broker...\r\n");
    printf("[MQTT] Broker: %s:%d\r\n",
           MQTT_BROKER_IP,
           MQTT_BROKER_PORT);
    printf("============================================\r\n");


    NetworkInit(&g_network);


    rc = NetworkConnect(
        &g_network,
        MQTT_BROKER_IP,
        MQTT_BROKER_PORT);


    if (rc != 0)
    {
        printf("[MQTT] TCP connection failed: %d\r\n", rc);
        return -1;
    }


    MQTTClientInit(
        &g_mqttClient,
        &g_network,
        2000,
        g_mqttSendBuf,
        sizeof(g_mqttSendBuf),
        g_mqttReadBuf,
        sizeof(g_mqttReadBuf));


    clientId.cstring = "bearpi-posture-monitor";


    data.clientID = clientId;
    data.willFlag = 0;
    data.MQTTVersion = 3;
    data.keepAliveInterval = 60;
    data.cleansession = 1;


    rc = MQTTConnect(
        &g_mqttClient,
        &data);


    if (rc != 0)
    {
        printf("[MQTT] MQTTConnect failed: %d\r\n", rc);

        NetworkDisconnect(&g_network);

        return -1;
    }


    /*
     * Start Paho background thread.
     *
     * Used for MQTT keepalive and future subscription support.
     */
    MQTTStartTask(&g_mqttClient);


    g_mqttReady = 1;


    printf("[MQTT] Connected successfully.\r\n");
    printf("[MQTT] Topic: %s\r\n",
           MQTT_STATUS_TOPIC);


    return 0;
}


/* =========================================================
 * MQTT publish
 * =========================================================
 */

static int PublishStatus(
    const char *state,
    int armed,
    int x,
    int y,
    int z,
    int baseChange,
    int stepChange,
    int temperature)
{
    int rc;
    int length;

    char payload[256];

    MQTTMessage message = {0};


    if (!g_mqttReady)
    {
        return -1;
    }


    length = snprintf(
        payload,
        sizeof(payload),

        "{"
        "\"armed\":%s,"
        "\"state\":\"%s\","
        "\"x\":%d,"
        "\"y\":%d,"
        "\"z\":%d,"
        "\"base\":%d,"
        "\"change\":%d,"
        "\"temperature\":%d"
        "}",

        armed ? "true" : "false",
        state,
        x,
        y,
        z,
        baseChange,
        stepChange,
        temperature);


    if (length <= 0 ||
        length >= (int)sizeof(payload))
    {
        printf("[MQTT] JSON payload too long.\r\n");

        return -1;
    }


    message.qos = QOS0;
    message.retained = 0;
    message.payload = payload;
    message.payloadlen = length;


    rc = MQTTPublish(
        &g_mqttClient,
        MQTT_STATUS_TOPIC,
        &message);


    if (rc != 0)
    {
        printf(
            "[MQTT] Publish failed: %d\r\n",
            rc);

        g_mqttReady = 0;

        return rc;
    }


    printf(
        "[MQTT] Published: %s\r\n",
        payload);


    return 0;
}


/* =========================================================
 * F1 button
 * =========================================================
 */

static void F1Pressed(char *arg)
{
    (void)arg;

    g_armToggleRequested = 1;
}


static void ButtonInit(void)
{
    IoSetFunc(
        WIFI_IOT_IO_NAME_GPIO_11,
        WIFI_IOT_IO_FUNC_GPIO_11_GPIO);


    GpioSetDir(
        WIFI_IOT_IO_NAME_GPIO_11,
        WIFI_IOT_GPIO_DIR_IN);


    IoSetPull(
        WIFI_IOT_IO_NAME_GPIO_11,
        WIFI_IOT_IO_PULL_UP);


    GpioRegisterIsrFunc(
        WIFI_IOT_IO_NAME_GPIO_11,
        WIFI_IOT_INT_TYPE_EDGE,
        WIFI_IOT_GPIO_EDGE_FALL_LEVEL_LOW,
        F1Pressed,
        NULL);
}


/* =========================================================
 * Posture state detection
 * =========================================================
 */

static MonitorState DetectState(
    int baseChange,
    int stepChange)
{
    if (stepChange >= IMPACT_THRESHOLD)
    {
        return STATE_IMPACT;
    }


    if (stepChange >= MOVING_THRESHOLD)
    {
        return STATE_MOVING;
    }


    if (baseChange <= NORMAL_BASE_THRESHOLD)
    {
        g_stableState = STATE_NORMAL;
    }
    else if (baseChange >= TILT_BASE_THRESHOLD)
    {
        g_stableState = STATE_TILT;
    }


    /*
     * 150 < BASE < 300
     *
     * Hysteresis region:
     * keep previous stable state.
     */
    return g_stableState;
}


/* =========================================================
 * LED
 * =========================================================
 */

static void UpdateLed(MonitorState state)
{
    /*
     * DISARMED
     */
    if (!g_armed)
    {
        LED_D1_StatusSet(OFF);
        LED_D2_StatusSet(OFF);

        return;
    }


    /*
     * ARMED + NORMAL
     */
    if (state == STATE_NORMAL)
    {
        LED_D1_StatusSet(ON);
        LED_D2_StatusSet(OFF);
    }
    else
    {
        /*
         * ARMED + abnormal
         */
        LED_D1_StatusSet(OFF);
        LED_D2_StatusSet(ON);
    }
}


/* =========================================================
 * ARM
 * =========================================================
 */

static void ArmSystem(void)
{
    E53_SC2_Read_Data();


    g_baseX =
        (int)E53_SC2_Data.Accel[0];

    g_baseY =
        (int)E53_SC2_Data.Accel[1];

    g_baseZ =
        (int)E53_SC2_Data.Accel[2];


    g_prevX = g_baseX;
    g_prevY = g_baseY;
    g_prevZ = g_baseZ;


    g_stableState = STATE_NORMAL;
    g_lastState = STATE_NORMAL;


    g_sampleCount = 0;
    g_heartbeatCounter = 0;


    g_armed = 1;


    UpdateLed(STATE_NORMAL);


    printf("\r\n");
    printf("============================================\r\n");
    printf("[SYSTEM] DISARMED -> ARMED\r\n");

    printf(
        "Baseline: X=%d Y=%d Z=%d\r\n",
        g_baseX,
        g_baseY,
        g_baseZ);

    printf("State: NORMAL\r\n");
    printf("============================================\r\n");


    /*
     * Publish immediately after ARM.
     */
    PublishStatus(
        "NORMAL",
        1,
        g_baseX,
        g_baseY,
        g_baseZ,
        0,
        0,
        (int)E53_SC2_Data.Temperature);
}


/* =========================================================
 * DISARM
 * =========================================================
 */

static void DisarmSystem(void)
{
    g_armed = 0;


    UpdateLed(STATE_NORMAL);


    printf("\r\n");
    printf("============================================\r\n");
    printf("[SYSTEM] ARMED -> DISARMED\r\n");
    printf("Monitoring stopped.\r\n");
    printf("D1 OFF / D2 OFF\r\n");
    printf("============================================\r\n");


    /*
     * Notify MQTT subscribers.
     */
    PublishStatus(
        "DISARMED",
        0,
        0,
        0,
        0,
        0,
        0,
        (int)E53_SC2_Data.Temperature);


    g_heartbeatCounter = 0;
}


/* =========================================================
 * Button request handling
 * =========================================================
 */

static void HandleButton(void)
{
    if (!g_armToggleRequested)
    {
        return;
    }


    g_armToggleRequested = 0;


    if (g_armed)
    {
        DisarmSystem();
    }
    else
    {
        ArmSystem();
    }


    /*
     * Button debounce
     */
    usleep(300000);


    g_armToggleRequested = 0;
}


/* =========================================================
 * Main monitor task
 * =========================================================
 */

static void PostureMonitorTask(void)
{
    int currentX;
    int currentY;
    int currentZ;

    int deltaX;
    int deltaY;
    int deltaZ;

    int stepX;
    int stepY;
    int stepZ;

    int baseChange;
    int stepChange;

    int stateChanged;

    MonitorState currentState;


    /*
     * Hardware init
     */
    E53_SC2_Init();

    ButtonInit();


    g_armed = 0;


    LED_D1_StatusSet(OFF);
    LED_D2_StatusSet(OFF);


    printf("\r\n");
    printf("============================================\r\n");
    printf("      Posture Monitor V4.3 Started          \r\n");
    printf("============================================\r\n");


    /*
     * Wi-Fi
     */
    printf("[WIFI] Connecting to %s ...\r\n",
           WIFI_SSID);


    if (WifiConnect(
            WIFI_SSID,
            WIFI_PASSWORD) != 0)
    {
        printf("[WIFI] Connection failed.\r\n");
    }
    else
    {
        printf("[WIFI] Connected.\r\n");


        /*
         * MQTT
         */
        if (MQTTConnectBroker() != 0)
        {
            printf(
                "[MQTT] Running in local-only mode.\r\n");
        }
    }


    printf("\r\n");
    printf("System: DISARMED\r\n");
    printf("D1 OFF / D2 OFF\r\n");
    printf("Press F1 to ARM.\r\n");
    printf("Press F1 again to DISARM.\r\n");
    printf("============================================\r\n");


    while (1)
    {
        /*
         * Handle ARM / DISARM first.
         */
        HandleButton();


        /*
         * DISARMED:
         * posture detection disabled.
         */
        if (!g_armed)
        {
            usleep(100000);

            continue;
        }


        /*
         * Read sensor
         */
        E53_SC2_Read_Data();


        currentX =
            (int)E53_SC2_Data.Accel[0];

        currentY =
            (int)E53_SC2_Data.Accel[1];

        currentZ =
            (int)E53_SC2_Data.Accel[2];


        /*
         * Difference from baseline
         */
        deltaX =
            currentX - g_baseX;

        deltaY =
            currentY - g_baseY;

        deltaZ =
            currentZ - g_baseZ;


        /*
         * Difference from previous sample
         */
        stepX =
            currentX - g_prevX;

        stepY =
            currentY - g_prevY;

        stepZ =
            currentZ - g_prevZ;


        baseChange =
            AbsInt(deltaX) +
            AbsInt(deltaY) +
            AbsInt(deltaZ);


        stepChange =
            AbsInt(stepX) +
            AbsInt(stepY) +
            AbsInt(stepZ);


        currentState =
            DetectState(
                baseChange,
                stepChange);


        UpdateLed(currentState);


        g_sampleCount++;
        g_heartbeatCounter++;


        printf(
            "N=%u "
            "A=(%d,%d,%d) "
            "BASE=%d "
            "CHANGE=%d "
            "STATE=%s\r\n",

            g_sampleCount,

            currentX,
            currentY,
            currentZ,

            baseChange,
            stepChange,

            GetStateName(currentState));


        stateChanged =
            (currentState != g_lastState);


        if (stateChanged)
        {
            printf(
                "[STATE] %s -> %s\r\n",

                GetStateName(g_lastState),
                GetStateName(currentState));
        }


        /*
         * MQTT publish policy:
         *
         * 1. State changed -> publish immediately
         * 2. Otherwise -> heartbeat every ~2 seconds
         */
        if (stateChanged ||
            g_heartbeatCounter >= MQTT_HEARTBEAT_SAMPLES)
        {
            PublishStatus(
                GetStateName(currentState),
                1,

                currentX,
                currentY,
                currentZ,

                baseChange,
                stepChange,

                (int)E53_SC2_Data.Temperature);


            g_heartbeatCounter = 0;
        }


        g_lastState =
            currentState;


        g_prevX = currentX;
        g_prevY = currentY;
        g_prevZ = currentZ;


        usleep(150000);
    }
}


/* =========================================================
 * OpenHarmony entry
 * =========================================================
 */

static void PostureMonitorEntry(void)
{
    osThreadAttr_t attr;


    attr.name =
        "PostureMonitorTask";

    attr.attr_bits = 0U;

    attr.cb_mem = NULL;
    attr.cb_size = 0U;

    attr.stack_mem = NULL;

    attr.stack_size =
        TASK_STACK_SIZE;

    attr.priority =
        TASK_PRIO;


    if (osThreadNew(
            (osThreadFunc_t)PostureMonitorTask,
            NULL,
            &attr) == NULL)
    {
        printf(
            "Failed to create PostureMonitorTask!\r\n");
    }
}


APP_FEATURE_INIT(PostureMonitorEntry);