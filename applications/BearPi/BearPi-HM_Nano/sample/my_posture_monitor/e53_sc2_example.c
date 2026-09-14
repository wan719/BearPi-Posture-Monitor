/*
 * Posture Monitor V3
 * Arm / Disarm + Four-state detection
 */

#include <stdio.h>
#include <unistd.h>

#include "ohos_init.h"
#include "cmsis_os2.h"
#include "E53_SC2.h"

#include "wifiiot_gpio.h"
#include "wifiiot_gpio_ex.h"

#define TASK_STACK_SIZE 1024 * 8
#define TASK_PRIO 25

/* V2-B 真机数据得到的阈值 */
#define NORMAL_BASE_THRESHOLD 150
#define TILT_BASE_THRESHOLD   300
#define MOVING_THRESHOLD      150
#define IMPACT_THRESHOLD      1500

E53_SC2_Data_TypeDef E53_SC2_Data;

typedef enum
{
    STATE_NORMAL = 0,
    STATE_TILT,
    STATE_MOVING,
    STATE_IMPACT
} MonitorState;

/* 当前是否已经布防 */
static int g_armed = 0;

/*
 * F1 中断中只设置标志位，
 * 不在中断回调里执行复杂逻辑。
 */
static volatile int g_armToggleRequested = 0;

/* 当前布防时记录的基准姿态 */
static int g_baseX = 0;
static int g_baseY = 0;
static int g_baseZ = 0;

/* 上一帧 */
static int g_prevX = 0;
static int g_prevY = 0;
static int g_prevZ = 0;

static unsigned int g_sampleCount = 0;

static MonitorState g_stableState = STATE_NORMAL;
static MonitorState g_lastState = STATE_NORMAL;

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

/*
 * F1 按键中断
 */
static void F1Pressed(char *arg)
{
    (void)arg;

    g_armToggleRequested = 1;
}

/*
 * 初始化 F1
 *
 * GPIO11：
 *   未按下 -> 高电平
 *   按下   -> 低电平
 */
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
     * 150 < BASE < 300：
     * 保持之前稳定状态，形成滞回。
     */
    return g_stableState;
}

/*
 * 根据当前工作状态控制 LED
 */
static void UpdateLed(MonitorState state)
{
    /*
     * 撤防状态：
     * D1、D2 全灭
     */
    if (!g_armed)
    {
        LED_D1_StatusSet(OFF);
        LED_D2_StatusSet(OFF);
        return;
    }

    /*
     * 布防 + 正常
     */
    if (state == STATE_NORMAL)
    {
        LED_D1_StatusSet(ON);
        LED_D2_StatusSet(OFF);
    }
    else
    {
        /*
         * 布防 + 异常
         */
        LED_D1_StatusSet(OFF);
        LED_D2_StatusSet(ON);
    }
}

/*
 * 布防
 *
 * 最关键的一点：
 * 按下 F1 布防时重新读取传感器，
 * 把“当前位置”记录成新的安全基准姿态。
 */
static void ArmSystem(void)
{
    E53_SC2_Read_Data();

    g_baseX = (int)E53_SC2_Data.Accel[0];
    g_baseY = (int)E53_SC2_Data.Accel[1];
    g_baseZ = (int)E53_SC2_Data.Accel[2];

    g_prevX = g_baseX;
    g_prevY = g_baseY;
    g_prevZ = g_baseZ;

    g_stableState = STATE_NORMAL;
    g_lastState = STATE_NORMAL;

    g_sampleCount = 0;

    g_armed = 1;

    UpdateLed(STATE_NORMAL);

    printf("\r\n");
    printf("============================================\r\n");
    printf("[SYSTEM] DISARMED -> ARMED\r\n");
    printf("Baseline captured:\r\n");
    printf("X=%d Y=%d Z=%d\r\n",
           g_baseX,
           g_baseY,
           g_baseZ);
    printf("State: NORMAL\r\n");
    printf("============================================\r\n");
}

/*
 * 撤防
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
}

/*
 * 处理 F1 请求
 */
static void HandleButton(void)
{
    if (!g_armToggleRequested)
    {
        return;
    }

    /*
     * 先清除本次中断标志
     */
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
     * 简单消抖：
     * 按键机械触点可能在一次按下时产生多个下降沿。
     */
    usleep(300000);

    /*
     * 消抖期间出现的重复中断直接丢弃
     */
    g_armToggleRequested = 0;
}

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

    MonitorState currentState;

    /*
     * E53_SC2_Init() 内部已经执行 GPIO 初始化，
     * 因此 ButtonInit() 不需要再次 GpioInit()。
     */
    E53_SC2_Init();

    ButtonInit();

    /*
     * 默认撤防
     */
    g_armed = 0;

    LED_D1_StatusSet(OFF);
    LED_D2_StatusSet(OFF);

    printf("\r\n");
    printf("============================================\r\n");
    printf("        Posture Monitor V3 Started          \r\n");
    printf("============================================\r\n");
    printf("System: DISARMED\r\n");
    printf("D1 OFF / D2 OFF\r\n");
    printf("\r\n");
    printf("Press F1 to ARM the system.\r\n");
    printf("Press F1 again to DISARM.\r\n");
    printf("============================================\r\n");

    while (1)
    {
        /*
         * 优先处理 F1
         */
        HandleButton();

        /*
         * 撤防状态不执行姿态报警判断。
         * 但线程仍然运行，以便随时响应 F1。
         */
        if (!g_armed)
        {
            usleep(100000);
            continue;
        }

        E53_SC2_Read_Data();

        currentX = (int)E53_SC2_Data.Accel[0];
        currentY = (int)E53_SC2_Data.Accel[1];
        currentZ = (int)E53_SC2_Data.Accel[2];

        deltaX = currentX - g_baseX;
        deltaY = currentY - g_baseY;
        deltaZ = currentZ - g_baseZ;

        stepX = currentX - g_prevX;
        stepY = currentY - g_prevY;
        stepZ = currentZ - g_prevZ;

        baseChange =
            AbsInt(deltaX) +
            AbsInt(deltaY) +
            AbsInt(deltaZ);

        stepChange =
            AbsInt(stepX) +
            AbsInt(stepY) +
            AbsInt(stepZ);

        currentState =
            DetectState(baseChange, stepChange);

        UpdateLed(currentState);

        g_sampleCount++;

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

        if (currentState != g_lastState)
        {
            printf(
                "[STATE] %s -> %s\r\n",
                GetStateName(g_lastState),
                GetStateName(currentState));

            g_lastState = currentState;
        }

        g_prevX = currentX;
        g_prevY = currentY;
        g_prevZ = currentZ;

        usleep(150000);
    }
}

static void PostureMonitorEntry(void)
{
    osThreadAttr_t attr;

    attr.name = "PostureMonitorTask";
    attr.attr_bits = 0U;
    attr.cb_mem = NULL;
    attr.cb_size = 0U;
    attr.stack_mem = NULL;
    attr.stack_size = TASK_STACK_SIZE;
    attr.priority = TASK_PRIO;

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