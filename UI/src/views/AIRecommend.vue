<template>
  <div class="ai-recommend-container">
    <div class="header-actions">
      <el-button type="danger" @click="clearHistory">
        清除历史记录
      </el-button>
    </div>

    <div class="chat-container">
      <div class="chat-messages" ref="chatMessages">
        <!-- 结构化推荐渲染整合到消息流中 -->
        <div
            v-for="(message, index) in conversationHistory"
            :key="index"
        >
          <!-- 结构化消息 -->
          <div
              v-if="message.structuredData"
              class="structured-container message ai-message"
          >
            <div class="header">
              <h2 class="title">{{ message.structuredData.title }}</h2>
              <div class="decorative-line"></div>
            </div>
            <div class="recommend-grid">
              <div
                  v-for="(item, idx) in message.structuredData.items"
                  :key="idx"
                  class="recommend-card"
              >
                <div class="card-header">
                  <span class="index">0{{ idx + 1 }}</span>
                  <h3 class="name">{{ item.name }}</h3>
                </div>
                <div class="card-body">
                  <p class="desc">{{ item.desc }}</p>
                </div>
              </div>
            </div>
            <div class="message-time">{{ message.timestamp }}</div>
          </div>


          <!-- 普通消息 -->
          <div
              v-else
              :class="['message', message.role === 'user' ? 'user-message' : 'ai-message']"
          >
            <div class="message-content">
              <p class="typewriter">{{ message.content }}</p>
            </div>
            <div class="message-time">{{ message.timestamp }}</div>
          </div>
        </div>
      </div>

      <div class="chat-input">
        <el-input
            v-model="userInput"
            type="textarea"
            :rows="3"
            placeholder="请输入您的问题，例如：我想去云南旅游，有什么推荐？"
            @keyup.enter.ctrl="sendMessage"
        />
        <div class="input-actions">
          <el-button type="primary" @click="sendMessage" :loading="loading">
            发送
          </el-button>
          <span class="input-tip">按 Ctrl + Enter 发送</span>
        </div>
      </div>
    </div>

    <el-empty
        v-if="!loading && conversationHistory.length === 0"
        description="请输入您的问题开始对话"
    />
  </div>
</template>

<script>
import axios from 'axios'
import { ref, onMounted, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import LZString from 'lz-string' // 确保已安装 lz-string

export default {
  name: 'AIRecommend',
  setup() {
    const router = useRouter()
    const conversationHistory = ref([])
    const loading = ref(false)
    const userInput = ref('')
    const chatMessages = ref(null)

    // 修复1: 将存储方法提升到setup顶层
    const loadConversationHistory = () => {
      try {
        const raw = localStorage.getItem('conversationHistory')
        if (!raw) return

        // 处理压缩数据
        const data = raw.startsWith('◌')
            ? LZString.decompress(raw)
            : raw

        const parseWithRetry = (jsonString) => {
          try {
            return JSON.parse(jsonString)
          } catch (e) {
            console.warn('尝试修复数据格式...')
            const repaired = jsonString
                .replace(/(['"])?([a-zA-Z0-9_]+)(['"])?:/g, '"$2":')
                .replace(/'/g, '"')
            return JSON.parse(repaired)
          }
        }

        const parsed = parseWithRetry(data)

        if (Array.isArray(parsed)) {
          conversationHistory.value = parsed.map(item => ({
            role: item.role || 'assistant',
            content: item.content || '',
            timestamp: item.timestamp || new Date().toLocaleString(),
            structuredData: item.structuredData ? {
              title: item.structuredData.title || '无标题',
              items: (item.structuredData.items || []).map(i => ({
                name: i.name || '未知景点',
                desc: i.desc || '暂无描述'
              }))
            } : null
          }))
        }
      } catch (e) {
        console.error('加载失败:', e)
        localStorage.removeItem('conversationHistory')
      }
    }

    // 修复2: 添加防抖的保存方法
    let saveTimeout = null
    const saveConversationHistory = () => {
      clearTimeout(saveTimeout)
      saveTimeout = setTimeout(() => {
        try {
          const data = JSON.stringify(conversationHistory.value)

          if (data.length > 1024 * 1024 * 5) { // 5MB
            const compressed = LZString.compress(data)
            localStorage.setItem('conversationHistory', compressed)
          } else {
            localStorage.setItem('conversationHistory', data)
          }
        } catch (e) {
          console.error('存储失败:', e)
          sessionStorage.setItem(
              'conversationBackup',
              JSON.stringify(conversationHistory.value)
          )
        }
      }, 500)
    }

    // 修复3: 添加深度监听
    watch(
        conversationHistory,
        (newVal) => {
          saveConversationHistory()
          nextTick(() => {
            if (chatMessages.value) {
              chatMessages.value.scrollTop = chatMessages.value.scrollHeight
            }
          })
        },
        { deep: true }
    )

    const initialize = async () => {
      try {
        // 存储可用性检测
        localStorage.setItem('__test__', 'test')
        localStorage.removeItem('__test__')

        // 修复4: 确保加载执行
        loadConversationHistory()
      } catch (e) {
        console.error('本地存储不可用:', e)
      }
    }

    onMounted(() => {
      initialize()
    })

    // 其他方法保持不变...
    const clearHistory = () => {
      if (confirm('确定要清除所有对话历史吗？')) {
        conversationHistory.value = []
        localStorage.removeItem('conversationHistory')
      }
    }
    const handleAIResponse = (data) => {
      try {
        if (data?.title && data?.items) {
          const structuredData = {
            title: data.title,
            items: data.items.map(item => ({
              name: item.name || '未知景点',
              desc: item.desc || '暂无描述'
            }))
          };

          const textContent = `${structuredData.title}\n\n` +
              structuredData.items.map((item, index) =>
                  `${index + 1}. ${item.name}：${item.desc}`
              ).join('\n');

          conversationHistory.value.push({
            role: 'assistant',
            content: textContent,
            structuredData: structuredData,
            timestamp: new Date().toLocaleString()
          });
          return true;
        }
      } catch (e) {
        console.error('结构化解析失败:', e);
      }
      return false;
    };

    const sendMessage = async () => {
      if (!userInput.value.trim()) return

      const userMessage = userInput.value.trim()
      const userMessage2 = `${userInput.value.trim()}，请加上最佳观赏时间`
      userInput.value = ''

      conversationHistory.value.push({
        role: 'user',
        content: userMessage,
        timestamp: new Date().toLocaleString()
      })

      loading.value = true

      try {
        const response = await axios.post('/api/ai/chat', {
          message: userMessage2,
          history: conversationHistory.value
        })

        // 处理响应（移除内部重复定义的handleAIResponse）
        if (handleAIResponse(response.data)) {
          return
        }

        // 降级处理
        const aiMessage = {
          role: 'assistant',
          content: typeof response.data === 'string'
              ? response.data
              : JSON.stringify(response.data),
          timestamp: new Date().toLocaleString()
        }
        conversationHistory.value.push(aiMessage)

      } catch (error) {
        console.error('请求失败:', error)
        conversationHistory.value.push({
          role: 'assistant',
          content: error.response?.data?.error || '服务暂时不可用',
          timestamp: new Date().toLocaleString()
        })
      } finally {
        loading.value = false
      }
    }

    return {
      conversationHistory,
      loading,
      userInput,
      chatMessages,
      sendMessage,
      clearHistory
    }
  }
}
</script>

<style scoped>
.ai-recommend-container {
  padding: 20px;
  max-width: 1200px;
  margin: 0 auto;
  min-height: calc(100vh - 40px);
  display: flex;
  flex-direction: column;
}

.header-actions {
  display: flex;
  gap: 10px;
  margin-bottom: 20px;
}

.chat-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  background-color: #fff;
  border-radius: 8px;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.1);
  padding: 20px;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  background-color: #f5f7fa;
  border-radius: 4px;
  margin-bottom: 20px;
  min-height: 400px;
}

/* 结构化推荐样式 */
.structured-container {
  padding: 20px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.05);
}

.header {
  text-align: center;
  margin-bottom: 30px;
}

.title {
  font-size: 28px;
  color: #2c3e50;
  margin-bottom: 10px;
  font-weight: 600;
  position: relative;
}

.decorative-line {
  width: 60px;
  height: 3px;
  background: #409EFF;
  margin: 0 auto;
  border-radius: 2px;
}

.recommend-grid {
  display: grid;
  gap: 20px;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
}

.recommend-card {
  background: #fff;
  border-radius: 8px;
  padding: 20px;
  border: 1px solid #ebeef5;
  transition: all 0.3s;
  cursor: pointer;
}

.recommend-card:hover {
  transform: translateY(-5px);
  box-shadow: 0 6px 12px rgba(0, 0, 0, 0.1);
}

.card-header {
  display: flex;
  align-items: center;
  margin-bottom: 15px;
}

.index {
  font-size: 24px;
  color: #409EFF;
  font-weight: 700;
  margin-right: 15px;
  opacity: 0.8;
}

/* 新增结构化消息的样式调整 */
.structured-container.message {
  margin-bottom: 30px;
  max-width: 100% !important;
}

/* 调整卡片间距 */
.recommend-card {
  margin-bottom: 10px;
}

/* 为结构化消息添加底部边距 */
.structured-container .message-time {
  margin-top: 15px;
  text-align: right;
}

.name {
  font-size: 18px;
  color: #2c3e50;
  margin: 0;
}

.desc {
  color: #666;
  line-height: 1.8;
  margin: 0;
  font-size: 14px;
}

/* 消息样式 */
.message {
  margin-bottom: 20px;
  max-width: 80%;
}

.user-message {
  margin-left: auto;
}

.ai-message {
  margin-right: auto;
}

.message-content {
  padding: 12px 16px;
  border-radius: 8px;
  background-color: white;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
}

.user-message .message-content {
  background-color: #409EFF;
  color: white;
}

.message-time {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
  text-align: right;
}

/* 输入区域样式 */
.chat-input {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.input-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.input-tip {
  font-size: 12px;
  color: #909399;
}

.typewriter {
  margin: 0;
  white-space: pre-wrap;
  word-wrap: break-word;
}

.message.ai-message .structured-container {
  background: #fff;
  padding: 15px;
  border-radius: 8px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
}

/* 确保文字颜色可见 */
.structured-container .title {
  color: #2c3e50 !important; /* 强制覆盖可能存在的继承颜色 */
}

.structured-container .desc {
  color: #666 !important;
}

/* 修复消息容器宽度 */
.ai-message {
  max-width: 80%;
  width: 100%;
}
</style>