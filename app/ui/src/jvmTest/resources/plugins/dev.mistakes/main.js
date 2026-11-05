// 错题本：一个会跑代码的插件。
//
// 这里没有 fs、没有网络、也看不见别的插件的数据。能看见的只有 data（宿主给的只读数据）
// 和 payload（用户刚按下的那个按钮带来的值）。
// 想存东西？只能返回要存什么，由宿主替你写。

trellis.register({
  cards: [
    {
      id: "notebook",
      point: "slot.home.section",
      order: 20,
      render: function (data) {
        var keys = [];
        for (var k in data.store) {
          if (k.indexOf("m:") === 0) keys.push(k);
        }
        keys.sort();
        keys.reverse();

        var children = [
          { type: "text", text: "错题本", style: "title" },
          { type: "text", text: "一共攒了 " + keys.length + " 道", style: "caption" },
          { type: "input", hint: "记一道错题", submitLabel: "记下", on: "add" },
          { type: "divider" }
        ];

        if (keys.length === 0) {
          children.push({ type: "text", text: "还没有。错了就记，别攒着。", style: "caption" });
        } else {
          keys.slice(0, 5).forEach(function (k) {
            children.push({
              type: "row",
              gap: 8,
              children: [
                { type: "text", text: data.store[k] },
                { type: "button", label: "删掉", on: "drop", args: { key: k } }
              ]
            });
          });
          if (keys.length > 5) {
            children.push({ type: "text", text: "还有 " + (keys.length - 5) + " 道更早的", style: "caption" });
          }
        }

        return { type: "column", gap: 8, children: children };
      }
    }
  ],
  on: {
    add: function (data, payload) {
      var text = (payload.value || "").trim();
      if (!text) return { say: "先写点什么" };
      var save = {};
      save["m:" + Date.now()] = text;
      return { say: "记下了", save: save };
    },
    drop: function (data, payload) {
      if (!payload.key) return { say: "不知道要删哪条" };
      return { say: "删掉了", remove: [payload.key] };
    }
  }
});
